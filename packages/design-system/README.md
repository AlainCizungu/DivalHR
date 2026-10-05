# @divalhr/design-system

Design tokens (`src/tokens.css`, light and dark) and the theme utilities (`system`, `light` and `dark` modes). React primitives live in `apps/web/src/ui` until a second application needs them (UI-001, D4).

## Identity mapping (UI-001, D3)

The tokens translate the DivalHR landing page into operational software. The landing page's colours, mark, wordmark and type are kept; its marketing layout (hero, gradients, large display headings) is not used in the application.

**Source.** The landing-page source archive `DivalHR-landing-page-source-b698321.tar.gz`, snapshot `b698321c9dac0c8d9c99b2b4f44996fb32728f1c`, authoritative files `app/globals.css`, `app/page.tsx`, `app/layout.tsx` and `public/favicon.svg`.

- The archive received has SHA-256 `6a2ceb2816e7f6b808bce11555829c4768dbc3a36c3638ab77c943670650f1c8`. The checksum published with the request (`cddf3c04…23ec2f`) did not match; the owner confirmed `6a2ceb28…` is the file he holds.
- The archive is a visual reference only and is not part of this repository.

| Landing page                      | Value                                         | Token(s)                                                                                                      | Use in the product                                                                                                                                     |
| --------------------------------- | --------------------------------------------- | ------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `--navy`                          | `#071b2b`                                     | dark `--dv-nav-*` family (`#041420`), icon background                                                         | Deep navy identity; the app icon tile.                                                                                                                 |
| App preview sidebar (`.dash-nav`) | `#082638`                                     | `--dv-nav-bg` (light)                                                                                         | Sidebar and drawer, as the landing page shows the product.                                                                                             |
| Preview nav text / active         | `#9fb4bf`, `#13b8a624` on navy, `#6ae4d6`     | `--dv-nav-text-muted`, `--dv-nav-active-bg` (`#0a3a47`, the same 14 % teal over navy), `--dv-nav-active-text` | Group labels, current page. Inactive links use a lighter `--dv-nav-text` (`#d3dfe4`) for legibility at body size.                                      |
| `--teal`                          | `#13b8a6`                                     | `--dv-color-primary`, `--dv-nav-indicator`, `--dv-brand-mark-from`, `--dv-nav-wordmark-accent`                | Primary buttons with the landing page's dark ink text `#032824`; current-page bar; "HR" in the wordmark in navigation.                                 |
| Button hover                      | `#24c8b6`                                     | `--dv-color-primary-hover`                                                                                    |                                                                                                                                                        |
| `--teal2` / preview active text   | `#62e0d2`, `#6ae4d6`                          | dark `--dv-color-primary`, `--dv-color-link`, `--dv-color-primary-subtle-text`                                | Teal on dark surfaces.                                                                                                                                 |
| Mark gradient end                 | `#177b9a`                                     | `--dv-brand-mark-to`                                                                                          | The "D" tile: `linear-gradient(140deg, teal, #177b9a)`, white letter.                                                                                  |
| `--gold`                          | `#d6a747`                                     | `--dv-color-accent`, `--dv-nav-focus`                                                                         | The eyebrow dot (as on the landing page) and the focus ring inside navigation. Gold text uses `--dv-color-accent-text` (`#8a6510` light) for contrast. |
| `--ink`                           | `#102231`                                     | `--dv-color-text`                                                                                             | Body text.                                                                                                                                             |
| `--muted`                         | `#5d6b75`                                     | `--dv-color-text-muted` (light)                                                                               | Secondary text.                                                                                                                                        |
| `--line`                          | `#dce5e8`                                     | `--dv-color-border`                                                                                           | Dividers and card borders.                                                                                                                             |
| `--paper`                         | `#f6faf9`                                     | `--dv-color-bg`                                                                                               | Page background.                                                                                                                                       |
| Warm highlight                    | `#fff3dc`                                     | `--dv-color-warning-bg`                                                                                       | Environment badge background.                                                                                                                          |
| Dark sections                     | `#061826`, `#0b3043`, `#071e2d`               | dark `--dv-color-bg`, `-surface-raised`, `-surface-sunken`                                                    | Dark theme surfaces.                                                                                                                                   |
| Type                              | `"Segoe UI", Inter, ui-sans-serif, system-ui` | `--dv-font-family`                                                                                            | Same stack. No web font is loaded, so the PWA stays offline-capable.                                                                                   |
| Radii                             | buttons 9px, nav items 7px, cards 15–16px     | `--dv-radius` (0.5625rem), `--dv-radius-sm` (0.4375rem), `--dv-radius-lg` (1rem)                              |                                                                                                                                                        |
| Brand weight                      | `font-weight: 730; letter-spacing: -.03em`    | `.brand__name`                                                                                                | Wordmark "Dival" + teal "HR".                                                                                                                          |
| `public/favicon.svg`              | navy tile, teal-gradient "D"                  | `apps/web/public/icon.svg`                                                                                    | App and PWA icon (accessible name added); theme colour `#082638`.                                                                                      |

**Translated, not copied.**

- The landing page's white-on-teal text pairs (2.5:1) are not used: primary buttons use the landing page's own dark ink on teal (6.3:1).
- Teal is never used as text on light surfaces (2.5:1); links and eyebrows use the darker `#0a7468` and `#0a6b60`.
- The wordmark's "HR" keeps the landing teal in the navy navigation (6.3:1). On light surfaces it uses the darker `--dv-brand-wordmark-accent` `#0c7d71` (5.0:1); on dark surfaces `#62e0d2`.
- The only gradient is the brand mark. Decorative hero gradients, grids, glows and display-size headings stay on the marketing site.

## Contrast

Measured with the WCAG 2.x relative-luminance formula. Text pairs need 4.5:1, focus rings and UI boundaries 3:1.

| Pair                               | Tokens                                                          | Light  | Dark   |
| ---------------------------------- | --------------------------------------------------------------- | ------ | ------ |
| Body text on page                  | `--dv-color-text` on `--dv-color-bg`                            | 15.4:1 | 15.5:1 |
| Body text on cards                 | `--dv-color-text` on `--dv-color-surface`                       | 16.2:1 | 13.4:1 |
| Secondary text                     | `--dv-color-text-muted` on `--dv-color-surface`                 | 5.5:1  | 7.2:1  |
| Secondary text on sunken           | `--dv-color-text-muted` on `--dv-color-surface-sunken`          | 4.9:1  | 7.9:1  |
| Primary action (teal)              | `--dv-color-primary-contrast` on `--dv-color-primary`           | 6.3:1  | 9.8:1  |
| Primary action, hover              | `--dv-color-primary-contrast` on `--dv-color-primary-hover`     | 7.5:1  | 10.3:1 |
| Links                              | `--dv-color-link` on `--dv-color-bg`                            | 5.4:1  | 11.2:1 |
| Icon tiles, eyebrows               | `--dv-color-primary-subtle-text` on `--dv-color-primary-subtle` | 5.8:1  | 8.0:1  |
| Gold text (rare)                   | `--dv-color-accent-text` on `--dv-color-surface`                | 5.3:1  | 9.1:1  |
| Navigation text (navy)             | `--dv-nav-text` on `--dv-nav-bg`                                | 11.5:1 | 13.7:1 |
| Navigation group labels            | `--dv-nav-text-muted` on `--dv-nav-bg`                          | 7.3:1  | 8.7:1  |
| Current page                       | `--dv-nav-active-text` on `--dv-nav-active-bg`                  | 8.0:1  | 9.4:1  |
| Wordmark "HR" in navigation        | `--dv-nav-wordmark-accent` on `--dv-nav-bg`                     | 6.3:1  | 7.5:1  |
| Wordmark "HR" on surfaces          | `--dv-brand-wordmark-accent` on `--dv-color-surface`            | 5.0:1  | 9.7:1  |
| Current-page bar (UI, 3:1)         | `--dv-nav-indicator` on `--dv-nav-bg`                           | 6.3:1  | 7.5:1  |
| Focus ring in navigation (UI, 3:1) | `--dv-nav-focus` on `--dv-nav-active-bg`                        | 5.5:1  | 6.5:1  |
| Focus ring on surfaces (UI, 3:1)   | `--dv-color-focus` on `--dv-color-surface`                      | 5.7:1  | 7.5:1  |
| Warning badge (environment)        | `--dv-color-warning` on `--dv-color-warning-bg`                 | 6.5:1  | 9.0:1  |
| Neutral badge (coming later)       | `--dv-color-neutral` on `--dv-color-neutral-bg`                 | 7.5:1  | 9.6:1  |
| Danger (error panel)               | `--dv-color-danger` on `--dv-color-danger-bg`                   | 5.7:1  | 7.1:1  |
| Success                            | `--dv-color-success` on `--dv-color-success-bg`                 | 5.5:1  | 8.0:1  |
| Info                               | `--dv-color-info` on `--dv-color-info-bg`                       | 7.3:1  | 8.7:1  |
| UI boundaries (3:1)                | `--dv-color-border-strong` on `--dv-color-surface`              | 3.5:1  | 3.5:1  |

Changing a value means re-measuring its pairs here. The end-to-end Axe checks then verify every page in both themes.

## Layout tokens

`--dv-sidebar-width` (16.5rem), `--dv-sidebar-rail` (4.5rem), `--dv-topbar-height` (3.75rem) and `--dv-content-max` (75rem). Breakpoints are in `em` (desktop ≥ 64em, tablet 48–64em, mobile < 48em), so browser zoom changes the layout.
