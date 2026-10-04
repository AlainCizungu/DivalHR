import type {
  ContractBlock,
  ContractPlaceholder,
  ContractTemplateProblemReason,
  ContractVoidReason,
  Problem,
} from '@divalhr/api-client';

/** MVP-030 template fields, in the contract's order. */
export const PLACEHOLDERS: readonly ContractPlaceholder[] = [
  'employee.givenNames',
  'employee.familyName',
  'employee.fullName',
  'employee.number',
  'organization.name',
  'legalEntity.name',
  'site.name',
  'employment.startDate',
  'contract.type',
  'contract.startDate',
  'contract.endDate',
  'issue.date',
];

export const VOID_REASONS: readonly ContractVoidReason[] = [
  'ISSUED_IN_ERROR',
  'WRONG_TEMPLATE',
  'WRONG_DATA',
  'OTHER',
];

export const LOCALES = ['fr', 'en'] as const;

/** Contract types that need an end date (D10). */
export const END_REQUIRED = new Set(['FIXED_TERM', 'APPRENTICESHIP', 'INTERNSHIP']);

const REASONS = new Set<string>([
  'EMPTY',
  'TOO_LONG',
  'CONTROL_CHARACTER',
  'UNSAFE_CHARACTER',
  'URI_SCHEME',
  'PROTOCOL_RELATIVE',
  'WEB_ADDRESS',
  'ENCODED_CONTENT',
  'MARKDOWN_LINK',
  'HTML_MARKUP',
  'UNKNOWN_PLACEHOLDER',
  'MALFORMED_PLACEHOLDER',
  'TOO_MANY_PLACEHOLDERS',
  'BRACES',
  'HEADING_EMPTY',
]);

/** A refused or failed request, reduced to stable codes and allow-listed details. */
export type ContractFailure = {
  messageKey: string;
  code?: string;
  /** A grammar problem: a closed reason and a line number, never the text. */
  problem?: { reason: ContractTemplateProblemReason; line: number };
  placeholder?: ContractPlaceholder;
  dateField?: 'startDate' | 'endDate';
  correlationId?: string;
  retryAfter?: number;
};

export const CONTRACT_NETWORK_FAILURE: ContractFailure = { messageKey: 'errors.network' };

/** Failures after which a preview must be repeated. */
export const PREVIEW_STALE = new Set(['CONTRACT_PREVIEW_CHANGED']);

/** Maps a Problem response; params outside the contract's allow-lists are dropped, not shown. */
export function contractFailureOf(
  response: Response,
  error: unknown,
  deniedKey = 'contracts.unauthorized',
): ContractFailure {
  const problem = error as Partial<Problem> | undefined;
  const params = (problem?.params ?? {}) as Record<string, unknown>;
  const retry = Number(response.headers.get('Retry-After'));
  const code = problem?.code;
  const failure: ContractFailure = {
    messageKey:
      response.status === 403 && code !== 'EMPLOYEE_LINK_REQUIRED'
        ? deniedKey
        : code
          ? `errors.${code}`
          : 'errors.generic',
    code,
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
  if (
    code === 'CONTRACT_TEMPLATE_INVALID' &&
    typeof params.reason === 'string' &&
    REASONS.has(params.reason) &&
    Number.isInteger(params.line)
  ) {
    failure.problem = {
      reason: params.reason as ContractTemplateProblemReason,
      line: params.line as number,
    };
  } else if (
    code === 'CONTRACT_VALUE_MISSING' &&
    typeof params.placeholder === 'string' &&
    (PLACEHOLDERS as readonly string[]).includes(params.placeholder)
  ) {
    failure.placeholder = params.placeholder as ContractPlaceholder;
  } else if (
    code === 'CONTRACT_DATES_INVALID' &&
    (params.field === 'startDate' || params.field === 'endDate')
  ) {
    failure.dateField = params.field;
  }
  return failure;
}

/** An instant in the user's language and the device's time zone. */
export function formatInstant(language: string, instant: string): string {
  return new Intl.DateTimeFormat(language, { dateStyle: 'long', timeStyle: 'short' }).format(
    new Date(instant),
  );
}

/**
 * A sample rendering of a draft for the administrator (grammar v1 line rules, client side):
 * fields become their bracketed labels, never real employee data. The server's rendering of an
 * issued contract is the only authority.
 */
export function sampleBlocks(body: string, label: (key: string) => string): ContractBlock[] {
  const blocks: ContractBlock[] = [];
  let paragraph: ContractBlock | null = null;
  const fill = (text: string) =>
    text.replace(/\{\{([A-Za-z.]+)\}\}/gu, (_match, key: string) => `[${label(key)}]`);
  for (const raw of body.replace(/\r\n?/gu, '\n').split('\n')) {
    const line = raw.trim();
    if (line === '') {
      paragraph = null;
      continue;
    }
    let type: ContractBlock['type'] = 'p';
    let text = line;
    if (line === '##' || line.startsWith('## ')) {
      type = 'h2';
      text = line.slice(2).trim();
    } else if (line === '#' || line.startsWith('# ')) {
      type = 'h1';
      text = line.slice(1).trim();
    } else if (line === '-' || line.startsWith('- ')) {
      type = 'li';
      text = line.slice(1).trim();
    }
    if (type === 'p' && paragraph) {
      paragraph.text = `${paragraph.text} ${fill(text)}`;
      continue;
    }
    const block: ContractBlock = { type, text: fill(text) };
    blocks.push(block);
    paragraph = type === 'p' ? block : null;
  }
  return blocks;
}
