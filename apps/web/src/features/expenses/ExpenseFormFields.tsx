import { useTranslation } from 'react-i18next';
import {
  CATEGORIES,
  CURRENCIES,
  DESCRIPTION_MAX,
  DESCRIPTION_MIN,
  MERCHANT_MAX,
  codePoints,
  earliestDate,
  isoDate,
  normalizeText,
  type ExpenseField,
  type ExpenseForm,
} from './expenses';

/** The fields of an expense, each with its label, help, error and accessible description. */
export function ExpenseFormFields({
  idPrefix,
  form,
  problems,
  onChange,
}: {
  idPrefix: string;
  form: ExpenseForm;
  problems: Set<ExpenseField>;
  onChange: (next: ExpenseForm) => void;
}) {
  const { t } = useTranslation();
  const today = new Date();
  const invalid = (field: ExpenseField) => (problems.has(field) ? true : undefined);
  const describedBy = (field: ExpenseField, help: boolean) =>
    [
      help ? `${idPrefix}-${field}-help` : null,
      problems.has(field) ? `${idPrefix}-${field}-error` : null,
    ]
      .filter(Boolean)
      .join(' ') || undefined;
  const error = (field: ExpenseField) =>
    problems.has(field) && (
      <p id={`${idPrefix}-${field}-error`} className="field__error">
        {t(`expenses.problems.${field}`)}
      </p>
    );
  return (
    <>
      {problems.size > 0 && (
        <div className="error-summary" role="alert" data-testid="form-problems">
          <p>{t('expenses.validation')}</p>
        </div>
      )}
      <div className="field">
        <label htmlFor={`${idPrefix}-currency`}>{t('expenses.fields.currency')}</label>
        <p id={`${idPrefix}-currency-help`} className="field__help">
          {t('expenses.fields.currencyHelp')}
        </p>
        <select
          id={`${idPrefix}-currency`}
          value={form.currency}
          aria-describedby={describedBy('currency', true)}
          onChange={(event) => {
            onChange({ ...form, currency: event.target.value as ExpenseForm['currency'] });
          }}
        >
          {CURRENCIES.map((code) => (
            <option key={code} value={code}>
              {t(`expenses.currency.${code}`)}
            </option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor={`${idPrefix}-amount`}>
          {t('expenses.fields.amount')} ({form.currency})
        </label>
        <p id={`${idPrefix}-amount-help`} className="field__help">
          {t(
            form.currency === 'CDF'
              ? 'expenses.fields.amountHelpCdf'
              : 'expenses.fields.amountHelpUsd',
          )}
        </p>
        {error('amount')}
        <input
          id={`${idPrefix}-amount`}
          type="text"
          inputMode="decimal"
          autoComplete="off"
          value={form.amount}
          aria-invalid={invalid('amount')}
          aria-describedby={describedBy('amount', true)}
          onChange={(event) => {
            onChange({ ...form, amount: event.target.value });
          }}
        />
      </div>
      <div className="field">
        <label htmlFor={`${idPrefix}-businessDate`}>{t('expenses.fields.businessDate')}</label>
        <p id={`${idPrefix}-businessDate-help`} className="field__help">
          {t('expenses.fields.businessDateHelp')}
        </p>
        {error('businessDate')}
        <input
          id={`${idPrefix}-businessDate`}
          type="date"
          min={earliestDate(today)}
          max={isoDate(today)}
          value={form.businessDate}
          aria-invalid={invalid('businessDate')}
          aria-describedby={describedBy('businessDate', true)}
          onChange={(event) => {
            onChange({ ...form, businessDate: event.target.value });
          }}
        />
      </div>
      <div className="field">
        <label htmlFor={`${idPrefix}-category`}>{t('expenses.fields.category')}</label>
        {error('category')}
        <select
          id={`${idPrefix}-category`}
          value={form.category}
          aria-invalid={invalid('category')}
          aria-describedby={describedBy('category', false)}
          onChange={(event) => {
            onChange({ ...form, category: event.target.value as ExpenseForm['category'] });
          }}
        >
          <option value="">{t('expenses.fields.categoryChoose')}</option>
          {CATEGORIES.map((category) => (
            <option key={category} value={category}>
              {t(`expenses.category.${category}`)}
            </option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor={`${idPrefix}-merchant`}>{t('expenses.fields.merchant')}</label>
        {error('merchant')}
        <input
          id={`${idPrefix}-merchant`}
          type="text"
          autoComplete="off"
          value={form.merchant}
          aria-invalid={invalid('merchant')}
          aria-describedby={describedBy('merchant', true)}
          onChange={(event) => {
            onChange({ ...form, merchant: event.target.value });
          }}
        />
        <p id={`${idPrefix}-merchant-help`} className="field__help">
          {t('expenses.count', { n: codePoints(normalizeText(form.merchant)), max: MERCHANT_MAX })}
        </p>
      </div>
      <div className="field">
        <label htmlFor={`${idPrefix}-description`}>{t('expenses.fields.description')}</label>
        {error('description')}
        <textarea
          id={`${idPrefix}-description`}
          rows={4}
          autoComplete="off"
          value={form.description}
          aria-invalid={invalid('description')}
          aria-describedby={describedBy('description', true)}
          onChange={(event) => {
            onChange({ ...form, description: event.target.value });
          }}
        />
        <p id={`${idPrefix}-description-help`} className="field__help">
          {t('expenses.fields.descriptionHelp', { min: DESCRIPTION_MIN, max: DESCRIPTION_MAX })}{' '}
          {t('expenses.count', {
            n: codePoints(normalizeText(form.description)),
            max: DESCRIPTION_MAX,
          })}
        </p>
      </div>
    </>
  );
}
