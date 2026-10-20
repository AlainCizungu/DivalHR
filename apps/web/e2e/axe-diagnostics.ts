// R150-2: what a failing `target-size` accessibility check leaves behind. The check failed in the
// browser suite without naming the element, so the shared `expectAccessible` helper now attaches
// one JSON artifact to the failing test, before its assertion throws. Nothing is produced when no
// `target-size` violation is reported, the axe rules are unchanged, and nothing here retries,
// waits, suppresses or excludes anything.
//
// Plain Node module (no Playwright import) so e2e/tooling.test.ts covers it without a browser.
// Privacy: no request, header, cookie or token is read. Page text is shortened and redacted
// (query strings, e-mail addresses, token-shaped and long opaque strings), and the artifact is
// written under test-results, which the credential hygiene check scans.
import type AxeBuilder from '@axe-core/playwright';

type AxeViolation = Awaited<ReturnType<AxeBuilder['analyze']>>['violations'][number];

export const SCHEMA = 'divalhr.e2e.target-size/1';
export const ARTIFACT_NAME = 'target-size-diagnostic';
const MAX_NEIGHBOURS = 5;

export interface Rect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface ElementFacts {
  tag: string;
  role: string | null;
  name: string;
  classes: string;
  testId: string | null;
  box: Rect;
  focused: boolean;
  hovered: boolean;
}

export interface Neighbour extends ElementFacts {
  /** The gap between the two boxes in CSS pixels; 0 when they touch or overlap. */
  distance: number;
}

export interface NodeContext {
  /** Null when the selector no longer resolves to exactly one element. */
  element: ElementFacts | null;
  neighbours: Neighbour[];
}

export interface PageContext {
  nodes: NodeContext[];
  /** The focused element and the innermost hovered element, if any. */
  focus: ElementFacts | null;
  hover: ElementFacts | null;
}

/** The part of a Playwright `Page` this module uses. */
export interface PageLike {
  url(): string;
  viewportSize(): { width: number; height: number } | null;
  evaluate<R, A>(pageFunction: (arg: A) => R, arg: A): Promise<R>;
}

export type Attach = (name: string, body: string, contentType: string) => Promise<void>;

const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}/gu;
const JWT_LIKE = /eyJ[A-Za-z0-9_-]{5,}(?:\.[A-Za-z0-9_-]*)*/gu;
const BEARER = /\bBearer\s+\S+/giu;
const OPAQUE = /[A-Za-z0-9_-]{32,}/gu;
const URL_WITH_QUERY = /((?:https?:\/\/|\/)[^\s"'<>?#]*)[?#][^\s"'<>]*/gu;
const LONG_DIGITS = /\d{6,}/gu;

/** Removes anything that could identify a person or authorize a request from page text. */
export function redact(text: string): string {
  return text
    .replace(BEARER, '[token]')
    .replace(JWT_LIKE, '[token]')
    .replace(EMAIL, '[email]')
    .replace(URL_WITH_QUERY, '$1')
    .replace(OPAQUE, '[redacted]')
    .replace(LONG_DIGITS, '[number]');
}

/** The path of a URL: no origin, no query string, no fragment. */
export function pathOf(url: string): string {
  try {
    return redact(new URL(url, 'http://localhost').pathname);
  } catch {
    return '[unparsed]';
  }
}

/** Every string in a JSON-like value, redacted; keys and structure are kept. */
export function redactDeep<T>(value: T): T {
  if (typeof value === 'string') return redact(value) as T;
  if (Array.isArray(value)) return value.map((item) => redactDeep(item as unknown)) as T;
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).map(([key, item]) => [
        key,
        redactDeep(item),
      ]),
    ) as T;
  }
  return value;
}

export function targetSizeViolations(violations: readonly AxeViolation[]): AxeViolation[] {
  return violations.filter((violation) => violation.id === 'target-size');
}

/** Every selector axe reported for the violations' nodes, in node order (one per node). */
export function selectorsOf(violations: readonly AxeViolation[]): (string | null)[] {
  return violations.flatMap((violation) =>
    violation.nodes.map((node) =>
      node.target.length === 1 && typeof node.target[0] === 'string' ? node.target[0] : null,
    ),
  );
}

/**
 * Runs in the browser. For each selector: the element's facts and the nearest interactive
 * neighbours with their distances; plus the focused and the innermost hovered element. Reads only
 * the DOM: no storage, no cookies, no network.
 */
export function inPage(selectors: (string | null)[]): PageContext {
  const limit = 80;
  const interactive =
    'a[href],button,input:not([type="hidden"]),select,textarea,summary,[role="button"],[role="link"],[role="tab"],[role="menuitem"],[tabindex]:not([tabindex="-1"])';
  const implicitRole = (el: Element): string | null => {
    const explicit = el.getAttribute('role');
    if (explicit) return explicit;
    const tag = el.tagName.toLowerCase();
    if (tag === 'a') return el.hasAttribute('href') ? 'link' : null;
    if (tag === 'button') return 'button';
    if (tag === 'select') return 'combobox';
    if (tag === 'textarea') return 'textbox';
    if (tag === 'input') {
      const type = (el.getAttribute('type') ?? 'text').toLowerCase();
      if (type === 'checkbox' || type === 'radio') return type;
      return type === 'button' || type === 'submit' ? 'button' : 'textbox';
    }
    return null;
  };
  const nameOf = (el: Element): string => {
    const label = el.getAttribute('aria-label');
    if (label) return label;
    const by = el.getAttribute('aria-labelledby');
    if (by) {
      const text = by
        .split(/\s+/u)
        .map((id) => document.getElementById(id)?.textContent ?? '')
        .join(' ')
        .trim();
      if (text) return text;
    }
    const own = el.textContent.replace(/\s+/gu, ' ').trim();
    return own || el.getAttribute('title') || el.getAttribute('alt') || '';
  };
  const facts = (el: Element) => {
    const r = el.getBoundingClientRect();
    return {
      tag: el.tagName.toLowerCase(),
      role: implicitRole(el),
      name: nameOf(el).slice(0, limit),
      classes: (el.getAttribute('class') ?? '').slice(0, 160),
      testId: el.getAttribute('data-testid'),
      box: { x: r.x, y: r.y, width: r.width, height: r.height },
      focused: el === document.activeElement,
      hovered: el.matches(':hover'),
    };
  };
  const gap = (a: DOMRect, b: DOMRect) => {
    const dx = Math.max(0, a.left - b.right, b.left - a.right);
    const dy = Math.max(0, a.top - b.bottom, b.top - a.bottom);
    return Math.hypot(dx, dy);
  };
  const resolve = (selector: string | null): Element | null => {
    if (selector === null) return null;
    try {
      const found = document.querySelectorAll(selector);
      return found.length === 1 ? found[0] : null;
    } catch {
      return null;
    }
  };
  const all = Array.from(document.querySelectorAll(interactive));
  const nodes = selectors.map((selector) => {
    const el = resolve(selector);
    if (!el) return { element: null, neighbours: [] };
    const box = el.getBoundingClientRect();
    const neighbours = all
      .filter((other) => other !== el && !el.contains(other) && !other.contains(el))
      .map((other) => ({ other, distance: gap(box, other.getBoundingClientRect()) }))
      .sort((a, b) => a.distance - b.distance)
      .slice(0, 5)
      .map(({ other, distance }) => ({ ...facts(other), distance }));
    return { element: facts(el), neighbours };
  });
  const hovered = Array.from(document.querySelectorAll(':hover')).pop() ?? null;
  const active = document.activeElement;
  return {
    nodes,
    focus: active && active !== document.body ? facts(active) : null,
    hover: hovered && hovered !== document.documentElement ? facts(hovered) : null,
  };
}

const round = (n: number) => Math.round(n * 10) / 10;
const roundBox = (box: Rect): Rect => ({
  x: round(box.x),
  y: round(box.y),
  width: round(box.width),
  height: round(box.height),
});
const roundFacts = <T extends ElementFacts>(facts: T): T => ({
  ...facts,
  box: roundBox(facts.box),
});

/** The artifact for the violations and the page context. Strings are redacted; numbers rounded. */
export function buildArtifact(input: {
  violations: readonly AxeViolation[];
  path: string;
  viewport: { width: number; height: number } | null;
  language: string;
  context: PageContext | null;
  failure?: string;
}) {
  const nodes = input.violations.flatMap((violation) => violation.nodes);
  return redactDeep({
    schema: SCHEMA,
    path: input.path,
    viewport: input.viewport,
    language: input.language,
    collection: input.failure ?? 'ok',
    // The complete raw axe objects for the rule, as axe returned them.
    violations: input.violations,
    nodes: nodes.map((node, index) => {
      const context = input.context?.nodes[index];
      return {
        target: node.target,
        html: node.html,
        impact: node.impact ?? null,
        failureSummary: node.failureSummary ?? null,
        element: context?.element ? roundFacts(context.element) : null,
        neighbours: (context?.neighbours ?? []).slice(0, MAX_NEIGHBOURS).map((neighbour) => ({
          ...roundFacts(neighbour),
          distance: round(neighbour.distance),
        })),
      };
    }),
    focus: input.context?.focus ? roundFacts(input.context.focus) : null,
    hover: input.context?.hover ? roundFacts(input.context.hover) : null,
  });
}

/**
 * Called by `expectAccessible` before its assertion. Does nothing, and touches nothing in the
 * page, unless axe reported `target-size`; never throws, so it can never replace the assertion's
 * own failure.
 */
export async function reportTargetSize(
  page: PageLike,
  violations: readonly AxeViolation[],
  attach: Attach,
): Promise<void> {
  const failing = targetSizeViolations(violations);
  if (failing.length === 0) return;
  let context: PageContext | null = null;
  let language = '';
  let failure: string | undefined;
  try {
    language = await page.evaluate(() => document.documentElement.lang, undefined);
    context = await page.evaluate(inPage, selectorsOf(failing));
  } catch (error) {
    failure = error instanceof Error ? error.name : 'error';
  }
  try {
    const artifact = buildArtifact({
      violations: failing,
      path: pathOf(page.url()),
      viewport: page.viewportSize(),
      language,
      context,
      failure,
    });
    await attach(ARTIFACT_NAME, JSON.stringify(artifact, null, 2), 'application/json');
  } catch {
    // The assertion that follows still reports the violation.
  }
}
