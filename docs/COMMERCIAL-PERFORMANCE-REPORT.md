# Commercial website performance report (LAUNCH-004, Issue #228)

Measured on 2026-11-26 from a production build of this branch (`pnpm --filter @divalhr/web build`, then `node scripts/ops/commercial-perf.mjs --write`). The script reads `apps/web/dist` only: no network and no browser. This is a build-size measurement, not a field or lab timing result (no Lighthouse or real-device run was made).

## Results

| Measure | Value |
| --- | --- |
| Initial JavaScript (one chunk) | 2,308 KB raw, 538.2 KB gzip, 406.9 KB brotli |
| Initial transfer (document, JS, CSS, config, hero image; gzip) | about 573 KB |
| Hero image (AVIF, 960 px variant a 1440 px desktop picks) | 25.7 KB |
| Largest commercial image file | 94.6 KB (limit 300 KB) |
| Commercial image files | 66 (AVIF, WebP, JPEG at 2 or 3 widths) plus one social preview |
| Video files bundled | 0 |
| Service-worker precache | 15 entries, 2,394.56 KiB |
| Precache ceiling | 3 MiB, unchanged |
| Images or source maps in the precache | 0 |

## Decisions that protect performance

1. The hero is the only eagerly loaded picture and is not on the critical path as a script or style; every other picture is lazy.
2. The reel is a sequence of the existing captures with captions, not a video file, and starts only when it scrolls into view.
3. No tracker, analytics script, third-party font or embed was added.
4. The Workbox ceiling was not raised. The glob patterns (`js, css, html, svg`) exclude images and source maps, and a unit test pins this.
5. `commercial-perf.mjs` exits 1 if the precache holds an image or `.map` file, or if a video file is bundled.

## Finding for Issue #211

Issue #211 hypothesizes that source maps are precached. In this build they are not: no `.map` file and no image appears in the precache. The precache is large because the whole app is one 2,308 KB JavaScript chunk. Reaching the 2 MiB target in #211 would need code splitting, which is outside this story and was not attempted.

## Limits

1. The commercial page's own contribution to the bundle is not isolated. A baseline build of `225da6c` was not made, so no before and after delta is claimed.
2. The initial transfer figure excludes fonts, icons and API calls.
3. No Core Web Vitals were measured. Any LCP claim would need a lab or field run, so none is made.

## Reproduce

```
pnpm --filter @divalhr/web build
node scripts/ops/commercial-perf.mjs --write
```

The raw output is `commercial-evidence/performance.json` (untracked, by repository convention).
