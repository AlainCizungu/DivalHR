import { canonicalOrigin } from './origins';

/**
 * LAUNCH-004: localized page metadata for the commercial page. The static tags in index.html carry
 * the French defaults (the document declares lang="fr") for crawlers that do not run scripts, without an image URL because the origin differs per environment; this applies the visitor's language and the social image.
 * No rating, price, customer count, award, office or testimonial is ever emitted.
 */
export interface PageMeta {
  title: string;
  description: string;
  ogTitle: string;
  ogDescription: string;
  ogImageAlt: string;
  locale: 'en' | 'fr';
}

const OG_LOCALE = { en: 'en_US', fr: 'fr_FR' } as const;

function setMeta(attribute: 'name' | 'property', key: string, content: string) {
  let element = document.head.querySelector<HTMLMetaElement>(`meta[${attribute}="${key}"]`);
  if (!element) {
    element = document.createElement('meta');
    element.setAttribute(attribute, key);
    document.head.appendChild(element);
  }
  element.setAttribute('content', content);
}

function setLink(rel: string, href: string) {
  let element = document.head.querySelector<HTMLLinkElement>(`link[rel="${rel}"]`);
  if (!element) {
    element = document.createElement('link');
    element.setAttribute('rel', rel);
    document.head.appendChild(element);
  }
  element.setAttribute('href', href);
}

export function structuredData(origin: string, description: string) {
  return {
    '@context': 'https://schema.org',
    '@graph': [
      {
        '@type': 'Organization',
        name: 'Dival AI',
        url: origin,
        email: 'contact@dival.ai',
      },
      {
        '@type': 'SoftwareApplication',
        name: 'DivalHR',
        applicationCategory: 'BusinessApplication',
        operatingSystem: 'Web',
        inLanguage: ['en', 'fr'],
        description,
        url: origin,
      },
    ],
  };
}

/** Applies the metadata and returns a function that restores the previous title. */
export function applyPageMeta(meta: PageMeta): () => void {
  const previousTitle = document.title;
  // LAUNCH-002: the canonical origin is the public commercial origin when one is configured.
  const origin = canonicalOrigin();
  document.title = meta.title;
  setMeta('name', 'description', meta.description);
  setMeta('property', 'og:type', 'website');
  setMeta('property', 'og:site_name', 'DivalHR');
  setMeta('property', 'og:title', meta.ogTitle);
  setMeta('property', 'og:description', meta.ogDescription);
  setMeta('property', 'og:locale', OG_LOCALE[meta.locale]);
  setMeta('property', 'og:url', `${origin}/`);
  setMeta('property', 'og:image', `${origin}/social-preview.png`);
  setMeta('property', 'og:image:alt', meta.ogImageAlt);
  setMeta('name', 'twitter:card', 'summary_large_image');
  setMeta('name', 'twitter:title', meta.ogTitle);
  setMeta('name', 'twitter:description', meta.ogDescription);
  setLink('canonical', `${origin}/`);

  let script = document.head.querySelector<HTMLScriptElement>('script#commercial-jsonld');
  if (!script) {
    script = document.createElement('script');
    script.id = 'commercial-jsonld';
    script.type = 'application/ld+json';
    document.head.appendChild(script);
  }
  script.textContent = JSON.stringify(structuredData(origin, meta.description));
  return () => {
    document.title = previousTitle;
  };
}
