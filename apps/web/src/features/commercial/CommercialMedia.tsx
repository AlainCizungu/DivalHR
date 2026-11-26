import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Icon } from '../../ui/Icon';
import manifest from './media.json';
import { langOf, type Lang } from './truth';

/**
 * LAUNCH-004 (Issue #228): real-product screenshots and the silent product reel. Screens come from
 * `media.json`, which docs/COMMERCIAL-SCREENSHOT-MANIFEST.md documents: route, locale, viewport,
 * release SHA and synthetic fixture for each capture. Nothing here is a mock-up, and nothing loads
 * until it is near the viewport.
 */
export const SHOT_ORDER = [
  'admin-home',
  'directory',
  'leave',
  'attendance',
  'payroll',
  'employee-mobile',
  'partners',
  'privacy',
] as const;
export type ShotId = (typeof SHOT_ORDER)[number];
type Viewport = 'desktop' | 'mobile';

export interface Shot {
  id: ShotId;
  locale: Lang;
  viewport: Viewport;
  width: number;
  height: number;
  /** Public path without width suffix or extension, e.g. /commercial/en-admin-home-desktop */
  base: string;
  widths: number[];
}

export const SHOTS = (manifest as unknown as { shots: Shot[] }).shots;

export function shotFor(id: ShotId, lang: Lang, viewport?: Viewport): Shot | undefined {
  return SHOTS.find(
    (shot) =>
      shot.id === id && shot.locale === lang && (viewport ? shot.viewport === viewport : true),
  );
}

function srcSet(shot: Shot, extension: string): string {
  return shot.widths.map((width) => `${shot.base}-${width}.${extension} ${width}w`).join(', ');
}

export function ShotPicture({
  shot,
  alt,
  sizes,
  priority = false,
}: {
  shot: Shot;
  alt: string;
  sizes: string;
  priority?: boolean;
}) {
  const largest = Math.max(...shot.widths);
  return (
    <picture>
      <source type="image/avif" srcSet={srcSet(shot, 'avif')} sizes={sizes} />
      <source type="image/webp" srcSet={srcSet(shot, 'webp')} sizes={sizes} />
      <img
        src={`${shot.base}-${largest}.jpg`}
        srcSet={srcSet(shot, 'jpg')}
        sizes={sizes}
        width={shot.width}
        height={shot.height}
        alt={alt}
        loading={priority ? 'eager' : 'lazy'}
        decoding="async"
        {...(priority ? { fetchPriority: 'high' as const } : {})}
      />
    </picture>
  );
}

export function HeroShot() {
  const { t, i18n } = useTranslation();
  const shot = shotFor('admin-home', langOf(i18n.resolvedLanguage), 'desktop');
  if (!shot) return null;
  return (
    <figure className="commercial-hero__shot" data-testid="commercial-hero-shot">
      <ShotPicture
        shot={shot}
        alt={t('commercial.hero.screenshotAlt')}
        sizes="(min-width: 960px) 52vw, 92vw"
        priority
      />
    </figure>
  );
}

export function Gallery() {
  const { t, i18n } = useTranslation();
  const lang = langOf(i18n.resolvedLanguage);
  const shots = SHOT_ORDER.flatMap((id) => SHOTS.filter((s) => s.id === id && s.locale === lang));
  if (shots.length === 0) return null;
  return (
    <section
      className="commercial-section commercial-section--alt"
      aria-labelledby="commercial-gallery-title"
      data-testid="commercial-gallery"
    >
      <div className="commercial-container">
        <h2 id="commercial-gallery-title">{t('commercial.media.galleryTitle')}</h2>
        <p className="commercial-lead">{t('commercial.media.galleryIntro')}</p>
        <ul className="commercial-gallery">
          {shots.map((shot) => (
            <li key={`${shot.id}-${shot.viewport}`} data-viewport={shot.viewport}>
              <figure>
                <ShotPicture
                  shot={shot}
                  alt={t(`commercial.media.shots.${shot.id}.alt`)}
                  sizes={
                    shot.viewport === 'mobile'
                      ? '(min-width: 960px) 20vw, 60vw'
                      : '(min-width: 960px) 44vw, 92vw'
                  }
                />
                <figcaption>
                  <b>{t(`commercial.media.shots.${shot.id}.title`)}</b>
                  <span>{t(`commercial.media.${shot.viewport}`)}</span>
                </figcaption>
              </figure>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}

const SCENES = [
  { id: 'brand', shot: undefined },
  { id: 'home', shot: 'admin-home' },
  { id: 'leave', shot: 'leave' },
  { id: 'attendance', shot: 'attendance' },
  { id: 'payroll', shot: 'payroll' },
  { id: 'employee', shot: 'employee-mobile' },
  { id: 'partners', shot: 'partners' },
  { id: 'cta', shot: undefined },
] as const satisfies readonly { id: string; shot: ShotId | undefined }[];

export const SCENE_SECONDS = 4;
export const REEL_SECONDS = SCENES.length * SCENE_SECONDS;

function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === 'function'
    ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
    : false;
}

/**
 * The silent product reel: the real captures in sequence with captions, muted by definition (it has
 * no audio), started only when it scrolls into view and never when the visitor prefers reduced
 * motion. A visible control pauses it and a transcript lists every scene.
 */
export function ProductReel() {
  const { t, i18n } = useTranslation();
  const lang = langOf(i18n.resolvedLanguage);
  const reduced = useRef(prefersReducedMotion());
  const [playing, setPlaying] = useState(false);
  const [index, setIndex] = useState(0);
  const root = useRef<HTMLElement>(null);

  useEffect(() => {
    const node = root.current;
    if (reduced.current || !node || typeof IntersectionObserver === 'undefined') return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        setPlaying(true);
        observer.disconnect();
      }
    });
    observer.observe(node);
    return () => {
      observer.disconnect();
    };
  }, []);

  useEffect(() => {
    if (!playing) return;
    const timer = window.setInterval(() => {
      setIndex((current) => (current + 1) % SCENES.length);
    }, SCENE_SECONDS * 1000);
    return () => {
      window.clearInterval(timer);
    };
  }, [playing]);

  const scene = SCENES[index] ?? SCENES[0];
  const shot = scene.shot
    ? shotFor(scene.shot, lang, scene.shot === 'employee-mobile' ? 'mobile' : 'desktop')
    : undefined;
  const caption = t(`commercial.media.reel.scenes.${scene.id}`);

  return (
    <section
      ref={root}
      className="commercial-section"
      aria-labelledby="commercial-reel-title"
      data-testid="commercial-reel"
      data-playing={playing}
    >
      <div className="commercial-container commercial-reel">
        <div>
          <h2 id="commercial-reel-title">{t('commercial.media.reel.title')}</h2>
          <p className="commercial-lead">{t('commercial.media.reel.mutedNote')}</p>
          <button
            type="button"
            className="button button--secondary"
            aria-pressed={playing}
            onClick={() => {
              setPlaying((value) => !value);
            }}
          >
            <Icon name={playing ? 'close' : 'spark'} />
            {playing ? t('commercial.media.reel.pause') : t('commercial.media.reel.play')}
          </button>
          <details className="commercial-transcript">
            <summary>{t('commercial.media.reel.transcriptToggle')}</summary>
            <h3>{t('commercial.media.reel.transcriptTitle')}</h3>
            <ol>
              {SCENES.map(({ id }) => (
                <li key={id}>{t(`commercial.media.reel.scenes.${id}`)}</li>
              ))}
            </ol>
          </details>
        </div>
        <figure
          className="commercial-reel__stage"
          data-scene={scene.id}
          data-testid="commercial-reel-stage"
        >
          {shot ? (
            <ShotPicture
              shot={shot}
              alt={t(`commercial.media.shots.${scene.shot ?? 'admin-home'}.alt`)}
              sizes="(min-width: 960px) 44vw, 92vw"
            />
          ) : (
            <div className="commercial-reel__slide" aria-hidden="true">
              <span className="commercial-badge-pill">{t('commercial.badge.pilot')}</span>
            </div>
          )}
          <figcaption>{caption}</figcaption>
        </figure>
      </div>
    </section>
  );
}
