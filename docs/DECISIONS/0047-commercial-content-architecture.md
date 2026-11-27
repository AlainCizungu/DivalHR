# ADR 0047: Commercial website content architecture and controlled-pilot acquisition (LAUNCH-004, Issue #228)

Status: proposed with the PR for Issue #228. Builds on ADR 0046 (privacy governance) and the LAUNCH-001 acceptance record. This is a content and delivery framework, not legal advice or certification.

## 1. Boundary (read first)

The public landing page becomes a bilingual (English and French) commercial website for a **controlled pilot**. It is a presentation layer over the product that exists. It adds no backend route, no data store, no analytics, no tracker, no form and no new product capability, and it does not redesign the signed-in shell.

- **The page may only say what the evidence supports.** Every capability on the page comes from one structured source, `apps/web/src/features/commercial/launch-truth.json`, and carries one of four customer-facing statuses.
- **No prospect data is collected or stored.** The demonstration and pilot calls to action are plain `mailto:` links to `contact@dival.ai`. There is no form, no third-party script, no calendar embed and no CRM.
- **Not generally available.** The primary status is "Controlled pilot / Pilote contrôlé". No page, metadata or caption claims general availability, production certification, a legal or regulatory approval, or a customer count, rating or price.

## 2. Decisions

- **C1 One truth source.** `launch-truth.json` lists 35 capabilities (id, pillar, status, evidence, gap, owner party). The page, `docs/COMMERCIAL-LAUNCH-TRUTH.md` and `docs/COMMERCIAL-LAUNCH-GAPS.md` are all produced from it (`scripts/ops/commercial-docs.mjs`), and `node --test` fails if a committed document differs from its rendering.
- **C2 Closed status catalogue.** `PILOT_AVAILABLE`, `CONFIGURATION_REQUIRED`, `PARTNER_REQUIRED`, `PLANNED`, shown to customers as "Available in the pilot", "Available after configuration", "Requires an approved partner" and "Planned" (French equivalents in the locale files, tested equal to the source). A capability needs evidence to be `PILOT_AVAILABLE` and a named gap otherwise, with no date estimates.
- **C3 Forbidden claims.** One list of English and French patterns (`forbidden-claims.json`) is applied to the whole locale namespace by the ops test and to the rendered page by the component tests. Disclosures that negate a claim are phrased so they do not match the pattern.
- **C4 Honest classification, flagged for review.** Where evidence is partial the lower status wins: payroll approval and payslips are `CONFIGURATION_REQUIRED`, partner referral is `PARTNER_REQUIRED`, and partner applications are `PILOT_AVAILABLE` for the public flow while the platform review queue is described only as verified on the controlled environment.
- **C5 Partner-dependent wording is verbatim.** The lending, insurance and money-movement disclosures appear in the exact approved English and French sentences, with test ids so tests compare them literally. DivalHR is never the lender, insurer or payment institution, and approval is never guaranteed.
- **C6 Integrations in four groups** (available connector, designed to connect, partner-dependent, planned). A brand mark is shown only with a local file and a documented source and usage basis in `docs/BRAND-ASSETS.md`; otherwise the name appears as text. A logo never means partnership, endorsement or a live connection, and a test fails on any unused or undocumented brand file.
- **C7 Anonymous frame.** A route flag, `anonymousFrame: 'landing'`, gives signed-out visitors the commercial header and footer on `/`, `/legal/privacy` and `/legal/terms`. `/partners/apply` and sign-in keep working, and signed-in users keep their shell. The root keeps `data-testid="landing"`.
- **C8 Localization.** All copy lives in the `commercial` namespace of the existing locale files. A test requires the same keys in both languages and fails on a French value equal to its English value, except for a short documented allowlist (for example a product or brand name).
- **C9 Real screenshots only.** The eight images are captures of the deployed release on hr-dev with synthetic data, recorded in `docs/COMMERCIAL-SCREENSHOT-MANIFEST.md` and `media.json`. One capture, the employee directory, has its generated employee numbers and names replaced by placeholders in the page before the screenshot, and says so in the gallery text and the manifest. Views whose fixture data was cluttered with test identifiers were left out, not masked or faked. Originals stay outside the bundle and outside version control.
- **C10 The reel is not a video file.** It sequences the real captures with captions: 6 scenes × 5 seconds (30 s), silent by construction, started only when it scrolls into view, never started under `prefers-reduced-motion`, with a visible play and pause control and a transcript (`docs/COMMERCIAL-REEL-TRANSCRIPT.md`). It adds no video or audio download and cannot delay first paint.
- **C11 Performance.** The hero image is the only eagerly loaded picture; everything else is lazy. Images are delivered as AVIF, WebP and JPEG at two or three widths, each at most 300 KB (the largest is under 100 KB). The service-worker precache ceiling is not raised, no source map is precached, and the navigation fallback deny-list is unchanged (`/auth`, `/api`, `/identity`, `/ai`).
- **C12 Accessibility.** WCAG 2.2 AA is the target: a skip link first, visible focus, 24 px minimum targets, reflow to 320 px, text-spacing overrides without clipping, status never conveyed by colour alone, motion only under `prefers-reduced-motion: no-preference`, descriptive alternative text. CSS uses classes only because the content-security policy forbids inline styles.
- **C13 Metadata.** Localized title, description, canonical, Open Graph and Twitter tags and structured data (Organization, SoftwareApplication) are set at runtime from the same locale strings, with no ratings, offers or counts. The static `index.html` carries French defaults (the document declares `lang="fr"`) for crawlers and link previews that do not run JavaScript: description and Open Graph title and description, no image URL because the origin differs per environment, and a test keeps them equal to the French locale strings. English metadata, the canonical link, the structured data and the social image are applied at runtime only, which is a stated limit, not a claim of prerendering.
- **C14 Analytics.** The events that would matter (pilot call to action, demo call to action, partner application start, language change) are documented only. Nothing is transmitted, because there is no approved first-party sink. Adding one is a separate story with its own privacy review.
- **C15 Legal pages.** `/legal/privacy` and `/legal/terms` are published as clearly labelled pilot documents pending review by counsel. They are a framework, not legal advice or certification.

## 3. Alternatives considered

1. **A separate marketing site or static-site generator.** Rejected for the pilot: it would duplicate the locale files and the status vocabulary and need its own deployment path. The single source keeps wording and evidence together.
2. **A real video of the product.** Rejected for now: it needs a pipeline for encoding, captions and a poster, would add a large asset, and a stale video would overstate the product faster than a screenshot. The reel can be replaced by a video later behind the same transcript.
3. **Masking more screens instead of leaving them out.** Rejected: every masked view is a view whose content is not fully real. One disclosed masked capture is the limit.
4. **A contact form or calendar link.** Rejected: it would store or transmit prospect data and add a processor and a privacy notice before the pilot has any approved data flow.

## 4. Consequences

1. Marketing wording changes go through `launch-truth.json` and the locale files, and fail CI if they break a rule. That is slower than free-form copy and intended.
2. The screenshots show a test environment with sparse data. They need recapturing, with the same script and manifest, when the product or a cleaner demonstration tenant changes.
3. Static crawlers see the French defaults only. If search visibility becomes a goal, prerendering is a separate decision.
4. Nothing here activates the retention policy, the real DRC statutory pack or any partner connection (see ADR 0046 and the gaps file).
