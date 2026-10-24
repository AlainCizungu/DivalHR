import type {
  WhatsAppConsentSource,
  WhatsAppLanguage,
  WhatsAppNotificationType,
  WhatsAppSelectableNotificationType,
} from '@divalhr/api-client';

export { failureOf, type Failure } from '../webhooks/webhooks';

/** The closed catalogue (D111-6), in display order. The test message is never selectable. */
export const SELECTABLE_TYPES: readonly WhatsAppSelectableNotificationType[] = [
  'LEAVE_DECISION',
  'TIMESHEET_APPROVED',
  'ATTENDANCE_CORRECTED',
  'PAYROLL_INPUT_LOCKED',
  'PAYROLL_EXPORT_READY',
];
export const TEMPLATE_TYPES: readonly WhatsAppNotificationType[] = [
  ...SELECTABLE_TYPES,
  'CONNECTOR_TEST',
];
export const LANGUAGES: readonly WhatsAppLanguage[] = ['en', 'fr'];
export const CONSENT_SOURCES: readonly WhatsAppConsentSource[] = [
  'WRITTEN',
  'WEB_FORM',
  'VERBAL_RECORDED_OUTSIDE_DIVALHR',
  'OTHER_DOCUMENTED',
];

/** Fixed limits shown to the administrator. */
export const MAX_RECIPIENTS = 25;
export const MAX_LABEL_LENGTH = 80;

const DIGITS = /^[0-9]{5,20}$/;
const E164 = /^\+[1-9][0-9]{6,14}$/;
const TEMPLATE_NAME = /^[a-z][a-z0-9_]{0,511}$/;
const TOKEN = /^[A-Za-z0-9._~+/=-]{20,1024}$/;

export const templateKey = (type: WhatsAppNotificationType, language: WhatsAppLanguage) =>
  `${type}/${language}`;

export type ConnectorValues = {
  wabaId: string;
  phoneNumberId: string;
  displayNumber: string;
  accessToken: string;
  /** Template name by `type/language`; an empty string skips the template. */
  templates: Record<string, string>;
};

export type ConnectorFieldErrors = Partial<
  Record<
    'wabaId' | 'phoneNumberId' | 'displayNumber' | 'accessToken' | 'templates' | 'confirm',
    string
  >
>;

/** A message key per field; the server remains authoritative. */
export function validateConnector(
  values: ConnectorValues,
  options: { needsToken: boolean; confirmed: boolean },
): ConnectorFieldErrors {
  const errors: ConnectorFieldErrors = {};
  if (!DIGITS.test(values.wabaId.trim())) errors.wabaId = 'whatsapp.errors.digitsInvalid';
  if (!DIGITS.test(values.phoneNumberId.trim()))
    errors.phoneNumberId = 'whatsapp.errors.digitsInvalid';
  if (!E164.test(values.displayNumber.trim()))
    errors.displayNumber = 'whatsapp.errors.displayNumberInvalid';
  if (options.needsToken) {
    if (values.accessToken.trim().length === 0)
      errors.accessToken = 'whatsapp.errors.tokenRequired';
    else if (!TOKEN.test(values.accessToken.trim()))
      errors.accessToken = 'whatsapp.errors.tokenInvalid';
  }
  const names = Object.values(values.templates)
    .map((name) => name.trim())
    .filter((name) => name.length > 0);
  if (names.length === 0) errors.templates = 'whatsapp.errors.templatesRequired';
  else if (names.some((name) => !TEMPLATE_NAME.test(name)))
    errors.templates = 'whatsapp.errors.templateNameInvalid';
  if (!options.confirmed) errors.confirm = 'whatsapp.errors.confirmRequired';
  return errors;
}

export function templateList(values: ConnectorValues) {
  return TEMPLATE_TYPES.flatMap((notificationType) =>
    LANGUAGES.flatMap((language) => {
      const templateName = (values.templates[templateKey(notificationType, language)] ?? '').trim();
      return templateName ? [{ notificationType, language, templateName }] : [];
    }),
  );
}

export type RecipientValues = {
  label: string;
  phoneNumber: string;
  language: WhatsAppLanguage;
  notificationTypes: WhatsAppSelectableNotificationType[];
  consentSource: WhatsAppConsentSource | '';
  consentDate: string;
  attested: boolean;
};

export type RecipientFieldErrors = Partial<
  Record<
    'label' | 'phoneNumber' | 'types' | 'consentSource' | 'consentDate' | 'attested' | 'confirm',
    string
  >
>;

export function validateRecipient(
  values: RecipientValues,
  options: { creating: boolean; confirmed: boolean; today: string },
): RecipientFieldErrors {
  const errors: RecipientFieldErrors = {};
  const label = values.label.trim();
  if (label.length === 0) errors.label = 'whatsapp.errors.labelRequired';
  else if (Array.from(label).length > MAX_LABEL_LENGTH)
    errors.label = 'whatsapp.errors.labelTooLong';
  else if (/[<>]|https?:\/\//iu.test(label)) errors.label = 'whatsapp.errors.labelInvalid';
  if (options.creating && !E164.test(values.phoneNumber.trim()))
    errors.phoneNumber = 'whatsapp.errors.phoneInvalid';
  if (values.notificationTypes.length === 0) errors.types = 'whatsapp.errors.typesRequired';
  if (options.creating) {
    if (!values.consentSource) errors.consentSource = 'whatsapp.errors.consentSourceRequired';
    if (!values.consentDate) errors.consentDate = 'whatsapp.errors.consentDateRequired';
    else if (values.consentDate > options.today)
      errors.consentDate = 'whatsapp.errors.consentDateFuture';
    if (!values.attested) errors.attested = 'whatsapp.errors.consentAttestRequired';
  }
  if (!options.confirmed) errors.confirm = 'whatsapp.errors.confirmRequired';
  return errors;
}

/**
 * The consent timestamp the server records: noon UTC of the chosen day, never later than now (the
 * server refuses a consent in the future).
 */
export function consentInstant(date: string, now: Date = new Date()): string {
  const candidate = new Date(`${date}T12:00:00Z`);
  return (candidate > now ? now : candidate).toISOString();
}

/** Today's date in the browser's time zone as YYYY-MM-DD (the latest consent date offered). */
export function today(now: Date = new Date()): string {
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${String(now.getFullYear())}-${month}-${day}`;
}
