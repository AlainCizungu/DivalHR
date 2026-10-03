import type { ReactNode } from 'react';

/**
 * A labelled region that scrolls a wide table sideways at narrow widths. It is focusable so the
 * table can be scrolled by keyboard (WCAG 2.1.1, axe scrollable-region-focusable).
 */
export function ScrollRegion({ label, children }: { label: string; children: ReactNode }) {
  return (
    // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- scrollable region (see above)
    <div className="table-wrapper" role="region" aria-label={label} tabIndex={0}>
      {children}
    </div>
  );
}
