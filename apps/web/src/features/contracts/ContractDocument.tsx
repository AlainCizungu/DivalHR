import type { ContractBlock, ContractIntegrity, ContractSnapshot } from '@divalhr/api-client';
import { useId, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

type Level = 2 | 3;

function Heading({ level, id, children }: { level: number; id?: string; children: ReactNode }) {
  if (level <= 2) return <h2 id={id}>{children}</h2>;
  if (level === 3) return <h3 id={id}>{children}</h3>;
  if (level === 4) return <h4 id={id}>{children}</h4>;
  return <h5 id={id}>{children}</h5>;
}

/** Consecutive list items form one list. */
function group(blocks: readonly ContractBlock[]): (ContractBlock | ContractBlock[])[] {
  const out: (ContractBlock | ContractBlock[])[] = [];
  for (const block of blocks) {
    const last = out.at(-1);
    if (block.type === 'li') {
      if (Array.isArray(last)) last.push(block);
      else out.push([block]);
    } else {
      out.push(block);
    }
  }
  return out;
}

/**
 * MVP-030: a contract snapshot as an article in its own language. Every block is a text node:
 * nothing is ever interpreted as markup or a link (A30-2, defense in depth). The language and
 * the integrity reference are printed in the footer.
 */
export function ContractDocument({
  snapshot,
  locale,
  integrity,
  level = 2,
  'data-testid': testId,
}: {
  snapshot: ContractSnapshot;
  locale: string;
  integrity?: ContractIntegrity;
  level?: Level;
  'data-testid'?: string;
}) {
  const { t, i18n } = useTranslation();
  const id = useId();
  return (
    <article
      className="contract-document"
      lang={locale}
      aria-labelledby={id}
      data-testid={testId ?? 'contract-document'}
    >
      <Heading level={level} id={id}>
        {snapshot.title}
      </Heading>
      {group(snapshot.blocks).map((item, index) =>
        Array.isArray(item) ? (
          <ul key={index}>
            {item.map((li, position) => (
              <li key={position}>{li.text}</li>
            ))}
          </ul>
        ) : item.type === 'h1' ? (
          <Heading key={index} level={level + 1}>
            {item.text}
          </Heading>
        ) : item.type === 'h2' ? (
          <Heading key={index} level={level + 2}>
            {item.text}
          </Heading>
        ) : (
          <p key={index}>{item.text}</p>
        ),
      )}
      <footer className="contract-document__footer muted" lang={i18n.language}>
        <p>{t('contracts.document.language', { language: t(`contracts.languages.${locale}`) })}</p>
        {integrity && (
          <p className="contract-document__digest">
            {t('contracts.document.integrity', { digest: integrity.snapshotSha256 })}
          </p>
        )}
      </footer>
    </article>
  );
}
