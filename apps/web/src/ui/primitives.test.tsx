import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { initI18n } from '../i18n';
import { Icon } from './Icon';
import {
  Breadcrumbs,
  EmptyState,
  ErrorPanel,
  LinkCard,
  NavGroup,
  PageHeader,
  Skeleton,
  StatusBadge,
} from './primitives';

const inRouter = async (ui: React.ReactNode) => {
  await initI18n('en');
  return render(<MemoryRouter>{ui}</MemoryRouter>);
};

describe('UI-001 primitives', () => {
  it('Icon is decorative', async () => {
    const { container } = await inRouter(<Icon name="home" />);
    const svg = container.querySelector('svg');
    expect(svg).toHaveAttribute('aria-hidden', 'true');
    expect(svg).toHaveAttribute('focusable', 'false');
  });

  it('PageHeader renders one h1 with its description', async () => {
    await inRouter(<PageHeader eyebrow="Documents" title="Contract templates" description="d" />);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Contract templates');
  });

  it('Breadcrumbs link pages, keep groups as text and mark the current page', async () => {
    await inRouter(
      <Breadcrumbs
        items={[{ label: 'Home', to: '/' }, { label: 'People' }, { label: 'Employees' }]}
      />,
    );
    const nav = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(within(nav).getAllByRole('link')).toHaveLength(1);
    expect(within(nav).getByText('Employees')).toHaveAttribute('aria-current', 'page');
    expect(within(nav).getByText('People')).not.toHaveAttribute('aria-current');
  });

  it('Breadcrumbs render nothing without items', async () => {
    const { container } = await inRouter(<Breadcrumbs items={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('NavGroup labels its links', async () => {
    await inRouter(
      <NavGroup label="People">
        <li>x</li>
      </NavGroup>,
    );
    expect(screen.getByRole('group', { name: 'People' })).toBeInTheDocument();
  });

  it('LinkCard has exactly one control, named by its title', async () => {
    await inRouter(
      <ul>
        <LinkCard to="/admin/people" icon="people" title="Employees" description="d" more="Open" />
      </ul>,
    );
    expect(screen.getAllByRole('link')).toHaveLength(1);
    expect(screen.getByRole('link', { name: 'Employees' })).toHaveAttribute(
      'href',
      '/admin/people',
    );
  });

  it('StatusBadge carries its meaning in text', async () => {
    await inRouter(
      <StatusBadge tone="warning" icon="environment" testId="b">
        Staging environment
      </StatusBadge>,
    );
    expect(screen.getByTestId('b')).toHaveTextContent('Staging environment');
  });

  it('EmptyState uses the requested heading level', async () => {
    await inRouter(<EmptyState icon="search" title="Page not found" titleLevel={1} />);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Page not found');
  });

  it('Skeleton is hidden from assistive technology', async () => {
    const { container } = await inRouter(<Skeleton lines={2} />);
    expect(container.firstElementChild).toHaveAttribute('aria-hidden', 'true');
  });

  it('ErrorPanel announces the message and keeps the caller test id (UI1-3)', async () => {
    await inRouter(<ErrorPanel message="No access" testId="not-authorized" />);
    expect(screen.getByRole('alert')).toHaveTextContent('No access');
    expect(screen.getByTestId('not-authorized')).toBe(screen.getByRole('alert'));
  });
});
