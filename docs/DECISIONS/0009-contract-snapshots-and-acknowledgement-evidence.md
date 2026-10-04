# ADR 0009 Contract snapshots and acknowledgement evidence

## Status

Accepted (Issue #51, architect approval with amendments A30-1 to A30-4; decisions D1–D15
approved as recommended, D16 provisionally).

## Context

MVP-030 lets a tenant administrator write contract templates, issue a contract from an approved
template to an employee, and lets the employee acknowledge receipt. Three modules are involved:
`documents` (an empty scaffold until now) owns templates, contracts and evidence; `people` owns
employees and employment (ADR 0007, ADR 0008); `identity` owns memberships and the
employee-access link (ADR 0008). `ARCHITECTURE.md` says documents use encrypted object storage,
with databases holding metadata; no object store, template engine or PDF library exists.

The design had to guarantee that:

- an issued contract never changes, whatever later happens to the template, the employee or the
  organization, and a stored contract can be verified against its digest;
- no template can carry markup, links, scripts or remote content into an employee's browser;
- an acknowledgement proves exactly which text, statement and person it binds, and nothing more:
  it is not an electronic signature;
- issue, void and acknowledgement serialize with separations and unlinks without deadlocks.

## Decision

### Module and storage (D1, D7)

- Templates, versions, contracts and acknowledgement evidence live in the existing `documents`
  module and schema. `people` and `identity` are reached only through `platform.access` ports
  that join the documents transaction (`MANDATORY`): `EmploymentContractFacts.lockForContract`
  (employment row `FOR SHARE`) and `EmployeeAccessLinks.linkedEmployee` (link and membership
  `FOR SHARE`). Unit names come from the existing `OrganizationPlacementDirectory`.
- **Scoped exception to object storage.** The rendered snapshot of a contract is generated text of
  at most 64 KiB. It is stored in PostgreSQL, in the issue transaction together with the audit
  and outbox records: `documents.contract.snapshot` (`jsonb`) and the exact canonical text
  `snapshot_canonical`, with a `CHECK` that they are equal and `snapshot_sha256`. No PDF is
  generated and no object is stored; the page has a print stylesheet. This exception covers
  generated contract text only. Uploads and generated files (MVP-031 onward) use encrypted
  object storage with a durable outbox-driven pipeline.

### Cross-schema keys (D2)

Like ADR 0008, the following foreign keys are a deliberate integrity exception (constraints,
never reads; application code uses the ports only):

- `documents.contract (tenant_id, employee_id) → people.employee`;
- `documents.contract (tenant_id, employment_id, employee_id) → people.employment`;
- `documents.contract_employment_guard (tenant_id, employment_id) → people.employment`;
- `documents.contract_acknowledgement (tenant_id, link_id, employee_id, membership_id) →
  identity.employee_access_link`.

`AccessPortArchitectureTest` scans the module sources so that `documents` never names a `people`
or `identity` table, and neither of them names a `documents` table.

### Grammar v1 and rendering (A30-2)

- Template text is plain text in a line grammar (`#`, `##`, `-`, paragraphs) with an allow-list of
  12 placeholders. Links, web addresses, URI schemes (in any case, fullwidth, zero-width or
  space-split), protocol-relative and UNC paths, percent, entity and backslash escapes, Markdown
  link and image syntax and HTML or event attributes are refused when a draft is saved, with
  `422 CONTRACT_TEMPLATE_INVALID` carrying a closed reason and a line number, never the text.
- Rendering parses the template into blocks first, then substitutes server values as literal text,
  so a value can never create structure. Dates and type labels come from server-owned tables
  (`renderer` version 1), not from the JDK's locale data.
- Clients render every block as a text node; there is no HTML path (defense in depth).

### Digests (A30-4)

Every digest is SHA-256 over an LF-joined, UTF-8, NFC preimage that starts with its own domain
separator and version: `DIVALHR-CONTRACT-TEMPLATE-BODY`, `-SNAPSHOT`, `-PREVIEW`,
`-ACKNOWLEDGEMENT-STATEMENT` and `-ACKNOWLEDGEMENT`, each `1`, with the grammar and renderer
versions in the snapshot, preview and evidence preimages. The server recomputes every digest
from its own data and constants and compares client values in constant time; it never stores a
client value. Version columns (`digest_version`, `grammar_version`, `renderer_version`,
`statement_version`, `statement_code`) are pinned by `CHECK` constraints, and golden tests pin
each preimage. A new algorithm, grammar, renderer or statement is a new migration and ADR.

### Lifecycle and immutability

- Template versions move `DRAFT → APPROVED → RETIRED`; only a never-approved draft is edited or
  deleted; one approved and one draft version per template and language (D8). Approval records
  the `TEXT_VERIFIED` confirmation: the organization verified the legal wording; DivalHR does not
  guarantee its validity.
- Contracts are inserted `ISSUED` from an approved version of their language and type, and move
  only `ISSUED → ACKNOWLEDGED` (with its evidence row at the same instant) or `ISSUED → VOID`.
  Acknowledgement evidence is insert-only and unique per contract; its composite foreign key
  carries the contract's snapshot digest and versions, so evidence cannot name another text.
  Non-void contracts of one employment never overlap (exclusion constraint, D11). No table can be
  truncated.

### Acknowledgement evidence (A30-3)

An acknowledgement records only that the authenticated employee, through their own membership
and active link, made the stated confirmation of the displayed snapshot. The evidence binds the
contract, employee, membership, link, snapshot digest and versions, statement code, version,
language and digest, and the database time. No IP address, user agent, drawn image, biometrics or
national ID is stored (D12). The interface says "Acknowledge" / « Accuser réception » and
"Acknowledged" / « Réception confirmée »; "signature" appears only in the negative disclaimer.
The version-1 statement wording is approved provisionally and needs counsel review before
production use (D16).

### Authorization and denial evidence (A30-1)

Administrator operations are `@TenantAdminOperation` and join MVP-013 durable denial evidence.
Employee operations are `@EmployeeSelfOperation` (`@TenantScoped(role = "employee")`, no MFA).
`ScopeAuthorizationInterceptor` writes a `platform.authorization_denial` row only for a privileged
role; employee self-service denials, and the business denial `403 EMPLOYEE_LINK_REQUIRED`, go to
the security log and the bounded counter `divalhr.employee.self_service.denials{operation,
reason}` only. Widening durable evidence to self-service would be a separate decision.

### Lock order (extends ADR 0008)

| Step | Lock | Path |
|---|---|---|
| 0 | Idempotency reservation | All writes |
| 2 | Employment row `FOR SHARE` (people port) | Issue |
| 4 | Link and membership `FOR SHARE` (identity port) | Acknowledge |
| 5c | `documents.contract_employment_guard` `FOR UPDATE`, then the template version `FOR SHARE` or the contract row `FOR UPDATE` | Issue, void, acknowledge |
| 6 | Deferred checks, audit, outbox | All writes |

A separation takes the employment row `FOR UPDATE` (step 2) and serializes with an issue; an unlink
takes the link `FOR UPDATE` (step 4) and serializes with an acknowledgement. No path takes an
earlier step after a later one. Approval and retirement take only the template and version rows.

## Consequences

- A contract's text can be read, printed and verified years later without a renderer or storage
  dependency; the read-only daily `ContractIntegrityJob` recomputes every stored digest and
  alerts on `divalhr.contract.integrity{outcome="mismatch"}`.
- The database holds Restricted HR text. It is protected by the production encryption-at-rest
  gate (MVP-020) and the privacy rules of `SECURITY.md`; nothing of it reaches logs, metrics,
  audit metadata, outbox data or URLs.
- A PDF or object-storage design is a later, separate decision; this ADR does not pre-empt it.
- The cross-schema keys couple the schemas' lifecycles: rolling back V16 refuses once any
  contract data exists, and `people` or `identity` changes to the referenced keys need this ADR
  reviewed.
