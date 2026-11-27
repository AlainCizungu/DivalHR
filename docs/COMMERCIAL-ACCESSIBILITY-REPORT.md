# Commercial website accessibility report (LAUNCH-004, Issue #228)

Target: WCAG 2.2 AA. This is an automated and scripted check plus a design review. It is not a manual audit with assistive technology, and it is not a certification of conformance.

## What was checked, and how

| Area | Method | Result on 2026-11-26 |
| --- | --- | --- |
| Automated rules (axe, tags wcag2a, wcag2aa, wcag21aa, wcag22aa, including target size) | `expectAccessible` in both journeys, English at 1440 px and French at the final state | No violations |
| Keyboard: skip link first | English journey: fresh load, one Tab, assert the skip link is focused | Pass |
| Visible focus | The focused skip link has a non-`none` outline of at least 2 px | Pass |
| Section navigation by keyboard | English journey reaches every section from the navigation | Pass |
| Reflow | Both journeys: no horizontal scroll at 320, 390 and 640 px | Pass |
| Text spacing (WCAG 1.4.12) | French journey: line height 1.5, letter spacing 0.12em, word spacing 0.16em, paragraph spacing 2em, no horizontal scroll | Pass after the fix below |
| Language | `<html lang>` follows the chosen language; the French journey asserts `fr` and the English journey `en` | Pass |
| Reduced motion | French journey emulates `prefers-reduced-motion: reduce`; the reel does not start | Pass |
| Images | Every real screenshot has descriptive alternative text (more than 60 characters, tested in the ops test) | Pass |

## Defects found and fixed during verification

1. **Text spacing overflow.** With the 1.4.12 overrides applied, the page scrolled horizontally by 418 px because the desktop header row and navigation were set not to wrap. Both now wrap. The test now names the first overflowing elements when it fails.
2. **Skip-link check.** The English journey pressed Tab with the keyboard starting point partway down the page, so it did not reach the skip link. This was a test defect: the page was correct. The test now reloads first.

## Design review (not measured by a tool)

1. Status is shown as text next to the badge, never by colour alone.
2. Interactive targets are at least 24 px, enforced by the axe `target-size` rule.
3. Motion runs only under `prefers-reduced-motion: no-preference`, and the reel has a visible play and pause control and a transcript (`docs/COMMERCIAL-REEL-TRANSCRIPT.md`). The reel is silent.
4. Styles are classes only because the content-security policy forbids inline styles.

## Not covered

1. No manual screen-reader run (NVDA, VoiceOver, TalkBack) was made.
2. Colour contrast is covered only by what axe can compute. Text on the photographic or gradient hero areas and in the screenshots themselves was not measured separately.
3. The screenshots are images of the product. Their text is described in the alternative text, not reproduced.
4. Dark mode and forced-colours mode were not tested.
5. Only the English desktop and French desktop journeys run axe. Mobile pages are checked for reflow, not for axe rules.

## Reproduce

```
pnpm --filter @divalhr/web exec playwright test --project=features e2e/commercial.spec.ts
```
