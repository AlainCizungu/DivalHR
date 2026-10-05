# @divalhr/design-system

Design tokens (`src/tokens.css`, light and dark) and the theme utilities (`system`, `light` and `dark` modes). React primitives live in `apps/web/src/ui` until a second application needs them (UI-001, D4).

## Identity mapping (UI-001, D3)

The tokens adapt the DivalHR landing-page identity for operational software. They keep its character (deep navy, teal, restrained gold, neutral surfaces) but take exact marketing colors only where those meet WCAG 2.2 AA.

| Identity | Tokens | Use in the product |
|---|---|---|
| Deep navy | `--dv-nav-*` | Sidebar and mobile drawer in both themes; the user avatar. Never page backgrounds in light mode. |
| Teal | `--dv-color-primary*`, `--dv-color-link`, `--dv-nav-indicator`, `--dv-brand-mark-bg` | Primary actions, links, current-page bar, icon tiles, the brand mark. |
| Restrained gold | `--dv-brand-mark-dot`, `--dv-color-accent*`, `--dv-nav-focus` | The dot on the brand mark and the focus ring inside navy navigation. Never body text, except `--dv-color-accent-text` where needed. |
| Neutral surfaces | `--dv-color-bg`, `--dv-color-surface*`, `--dv-color-border*`, `--dv-color-text*` | Pages, cards, menus, roadmap tiles, dividers. |
| Status | `--dv-color-{success,warning,danger,info,neutral}` with `-bg` | Badges and panels. Every status also carries text; colour only reinforces it. |

There are no decorative gradients and no web fonts: the system font stack keeps the PWA fast and offline-capable.

## Contrast

Measured with the WCAG 2.x relative-luminance formula. Text pairs need 4.5:1, focus rings and UI boundaries 3:1.

| Pair | Tokens | Light | Dark |
|---|---|---|---|
| Body text on page | `--dv-color-text` on `--dv-color-bg` | 15.6:1 | 15.9:1 |
| Body text on cards | `--dv-color-text` on `--dv-color-surface` | 16.7:1 | 14.6:1 |
| Secondary text | `--dv-color-text-muted` on `--dv-color-surface` | 6.9:1 | 8.1:1 |
| Primary action (teal) | `--dv-color-primary-contrast` on `--dv-color-primary` | 5.5:1 | 7.8:1 |
| Links | `--dv-color-link` on `--dv-color-bg` | 7.0:1 | 12.7:1 |
| Icon tiles, eyebrows | `--dv-color-primary-subtle-text` on `--dv-color-primary-subtle` | 6.6:1 | 9.7:1 |
| Gold text (rare) | `--dv-color-accent-text` on `--dv-color-surface` | 6.3:1 | 10.3:1 |
| Navigation text (navy) | `--dv-nav-text` on `--dv-nav-bg` | 13.6:1 | 15.2:1 |
| Navigation group labels | `--dv-nav-text-muted` on `--dv-nav-bg` | 7.5:1 | 8.4:1 |
| Current page | `--dv-nav-active-text` on `--dv-nav-active-bg` | 11.5:1 | 13.1:1 |
| Current-page bar (UI, 3:1) | `--dv-nav-indicator` on `--dv-nav-bg` | 8.9:1 | 9.9:1 |
| Focus ring in navigation (UI, 3:1) | `--dv-nav-focus` on `--dv-nav-active-bg` | 6.8:1 | 7.8:1 |
| Focus ring on surfaces (UI, 3:1) | `--dv-color-focus` on `--dv-color-surface` | 5.7:1 | 8.3:1 |
| Warning badge (environment) | `--dv-color-warning` on `--dv-color-warning-bg` | 6.5:1 | 9.0:1 |
| Neutral badge (coming later) | `--dv-color-neutral` on `--dv-color-neutral-bg` | 7.5:1 | 9.6:1 |
| Danger (error panel) | `--dv-color-danger` on `--dv-color-danger-bg` | 5.7:1 | 7.1:1 |
| Success | `--dv-color-success` on `--dv-color-success-bg` | 5.5:1 | 8.0:1 |
| Info | `--dv-color-info` on `--dv-color-info-bg` | 7.3:1 | 8.7:1 |
| UI boundaries (3:1) | `--dv-color-border-strong` on `--dv-color-surface` | 3.5:1 | 3.4:1 |

Changing a value means re-measuring its pairs here. The end-to-end Axe checks then verify every page in both themes.

## Layout tokens

`--dv-sidebar-width` (16.5rem), `--dv-sidebar-rail` (4.5rem), `--dv-topbar-height` (3.75rem) and `--dv-content-max` (75rem). Breakpoints are in `em` (desktop ≥ 64em, tablet 48–64em, mobile < 48em), so browser zoom changes the layout.
