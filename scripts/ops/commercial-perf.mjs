#!/usr/bin/env node
// LAUNCH-004 (Issue #228): measures the built web app for the commercial page. Run after
// `pnpm --filter @divalhr/web build`:  node scripts/ops/commercial-perf.mjs [--write]
// It reads apps/web/dist only: no network, no browser, no dependency.
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { brotliCompressSync, constants, gzipSync } from 'node:zlib';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const DIST = join(ROOT, 'apps/web/dist');
const kb = (bytes) => Math.round((bytes / 1024) * 10) / 10;

function files(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory() ? files(join(dir, entry.name)) : [join(dir, entry.name)],
  );
}
function sizes(path) {
  const body = readFileSync(path);
  return {
    raw: body.length,
    gzip: gzipSync(body, { level: 9 }).length,
    brotli: brotliCompressSync(body, {
      params: { [constants.BROTLI_PARAM_QUALITY]: 11 },
    }).length,
  };
}

const html = readFileSync(join(DIST, 'index.html'), 'utf8');
const refs = [
  ...html.matchAll(/<script[^>]+src="([^"]+)"/gu),
  ...html.matchAll(/<link[^>]+rel="(?:modulepreload|stylesheet)"[^>]*href="([^"]+)"/gu),
  ...html.matchAll(/<link[^>]+href="([^"]+)"[^>]*rel="(?:modulepreload|stylesheet)"/gu),
].map((match) => match[1]);
const initial = [...new Set(refs)].filter((href) => href.startsWith('/'));
const rows = initial.map((href) => ({ href, ...sizes(join(DIST, href)) }));
const sum = (list, key) => list.reduce((total, row) => total + row[key], 0);
const js = rows.filter((row) => row.href.endsWith('.js'));
const css = rows.filter((row) => row.href.endsWith('.css'));
const htmlSizes = sizes(join(DIST, 'index.html'));
const configSizes = (() => {
  try {
    return sizes(join(DIST, 'config.js'));
  } catch {
    return { raw: 0, gzip: 0, brotli: 0 };
  }
})();

// The hero is the English or French administrator home; the browser picks one width.
const publicFiles = files(join(DIST, 'commercial'));
const hero = (lang, extension) =>
  [640, 960, 1440].map((width) => {
    const path = join(DIST, 'commercial', `${lang}-admin-home-desktop-${width}.${extension}`);
    return { file: relative(DIST, path), bytes: statSync(path).size };
  });
const heroPick = hero('en', 'avif').find((item) => item.file.includes('-960.'));
const largest = publicFiles
  .map((path) => ({ file: relative(DIST, path), bytes: statSync(path).size }))
  .sort((a, b) => b.bytes - a.bytes)[0];
const video = publicFiles.filter((path) => /\.(mp4|webm|mov)$/u.test(path));

// The service worker's precache list.
const swSource = readFileSync(join(DIST, 'sw.js'), 'utf8');
const precache = [...swSource.matchAll(/url:\s*"([^"]+)"/gu)].map((match) => match[1]);
const precacheBad = precache.filter((url) => /\.(map|png|jpe?g|webp|avif|mp4|webm)$/u.test(url));
const precacheBytes = precache.reduce((total, url) => {
  try {
    return total + statSync(join(DIST, url)).size;
  } catch {
    return total;
  }
}, 0);
const biggestPrecached = precache
  .map((url) => {
    try {
      return { url, bytes: statSync(join(DIST, url)).size };
    } catch {
      return { url, bytes: 0 };
    }
  })
  .sort((a, b) => b.bytes - a.bytes)[0];

const result = {
  measuredAt: new Date().toISOString(),
  initialAssets: rows.map((row) => ({
    href: row.href,
    rawKB: kb(row.raw),
    gzipKB: kb(row.gzip),
    brotliKB: kb(row.brotli),
  })),
  initialJavaScript: {
    rawKB: kb(sum(js, 'raw')),
    gzipKB: kb(sum(js, 'gzip')),
    brotliKB: kb(sum(js, 'brotli')),
  },
  initialCss: {
    rawKB: kb(sum(css, 'raw')),
    gzipKB: kb(sum(css, 'gzip')),
    brotliKB: kb(sum(css, 'brotli')),
  },
  initialTransfer: {
    note: 'document + initial JS + initial CSS + config.js + the hero image the browser picks at a 1440 px desktop, compressed with gzip; fonts, icons and the API are not counted',
    gzipKB: kb(
      htmlSizes.gzip +
        sum(js, 'gzip') +
        sum(css, 'gzip') +
        configSizes.gzip +
        (heroPick?.bytes ?? 0),
    ),
    brotliKB: kb(
      htmlSizes.brotli +
        sum(js, 'brotli') +
        sum(css, 'brotli') +
        configSizes.brotli +
        (heroPick?.bytes ?? 0),
    ),
    heroImage: heroPick,
  },
  largestCommercialFile: largest && { ...largest, KB: kb(largest.bytes) },
  commercialFiles: publicFiles.length,
  videoFiles: video.map((path) => relative(DIST, path)),
  precache: {
    entries: precache.length,
    totalKB: kb(precacheBytes),
    biggest: biggestPrecached && { ...biggestPrecached, KB: kb(biggestPrecached.bytes) },
    forbiddenEntries: precacheBad,
  },
};
console.log(JSON.stringify(result, null, 2));
if (process.argv.includes('--write')) {
  writeFileSync(
    join(ROOT, 'commercial-evidence/performance.json'),
    JSON.stringify(result, null, 2) + '\n',
  );
}
if (precacheBad.length > 0 || video.length > 0) {
  console.error('FAIL: precache contains images or source maps, or a video file is bundled');
  process.exit(1);
}
