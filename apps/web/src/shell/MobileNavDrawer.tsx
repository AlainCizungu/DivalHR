import { useEffect, useRef, type RefObject } from 'react';
import { useTranslation } from 'react-i18next';
import { Icon } from '../ui/Icon';
import { Brand, NavigationList } from './Navigation';
import type { NavGroupDef } from './navigation';

/**
 * UI-001 mobile navigation: a native modal dialog. showModal() makes the page behind it inert and
 * keeps focus inside; Esc, the close button, a click on the backdrop and following a link all close
 * it, and focus returns to the menu button.
 */
export function MobileNavDrawer({
  id,
  open,
  onClose,
  returnFocusTo,
  groups,
  activePath,
  loading,
}: {
  id: string;
  open: boolean;
  onClose: () => void;
  returnFocusTo: RefObject<HTMLButtonElement | null>;
  groups: NavGroupDef[];
  activePath: string | null;
  loading: boolean;
}) {
  const { t } = useTranslation();
  const dialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      dialog.showModal();
      dialog.querySelector<HTMLElement>('.nav-link')?.focus();
    } else if (!open && dialog.open) {
      dialog.close();
    }
  }, [open]);

  return (
    // The dialog closes on Esc natively (cancel); a click on its own box is a click on the backdrop.
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- backdrop click and Tab wrapping; Esc is the native cancel event
    <dialog
      ref={dialogRef}
      id={id}
      className="drawer"
      aria-label={t('nav.primary')}
      onClose={() => {
        onClose();
        returnFocusTo.current?.focus();
      }}
      onClick={(event) => {
        if (event.target === event.currentTarget) dialogRef.current?.close();
      }}
      onKeyDown={(event) => {
        // Keep Tab cycling inside the drawer (the page behind is inert, but the browser would
        // otherwise move focus to its own toolbar after the last link).
        if (event.key !== 'Tab') return;
        const focusable = Array.from(
          event.currentTarget.querySelectorAll<HTMLElement>('a[href], button:not([disabled])'),
        );
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (!first || !last) return;
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first.focus();
        }
      }}
    >
      {open && (
        <div className="drawer__panel">
          <div className="drawer__header">
            <Brand />
            <button
              type="button"
              className="drawer__close"
              onClick={() => dialogRef.current?.close()}
            >
              <Icon name="close" />
              <span className="visually-hidden">{t('shell.closeNavigation')}</span>
            </button>
          </div>
          <nav className="drawer__nav" aria-label={t('nav.primary')}>
            <NavigationList
              groups={groups}
              activePath={activePath}
              loading={loading}
              onNavigate={() => dialogRef.current?.close()}
            />
          </nav>
        </div>
      )}
    </dialog>
  );
}
