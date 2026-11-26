# LAUNCH-001 threat model (Issue #226, ADR 0046)

Scope: partner-referral data governance, employee data-rights requests, the access package and the purge. This is a governance framework, not legal advice or a certification.

## Assets

Employee partner-referral values (disclosure), the consent record, delivery diagnostics, lifecycle evidence, retention policies, holds, data-rights requests (and their sealed notes), the encrypted access package, purge evidence.

## Actors

Employee (own data only); tenant administrator, manager, approver (no access); platform administrator with exact MFA (governance and purge); an attacker holding a stolen token without MFA; an insider with database access.

## Threats and controls

1. **Premature or wrongful deletion.** Controls: no scheduler or background deletion; policy must be reviewed (checklist) and active; a referral stays `POLICY_PENDING` until a previewed, confirmed application; the database guard refuses a purge item that is not closed, has no stamped rule, is on hold or has not elapsed; preview then exact-MFA confirmation with an idempotency key; revalidation under locks; a stale or reused preview is refused.
2. **A policy change that shortens a promise.** Control: a stamped referral keeps its stamped rules; policy rows are immutable after approval; overlap guarded per country with an advisory lock.
3. **Deletion order leaves a dangling or unprovable state.** Control: fixed order (disclosure, diagnostics, consent last); deferred completeness triggers tolerate only purged classes; consent is removed only when disclosure and diagnostics are gone or going.
4. **Employer or another employee learns a private choice or request.** Controls: employer roles are refused before arguments are read; own-reference lookups return the same not-found for unknown and foreign; platform views mask contact and never return a note, a private choice or a value; no notification channel.
5. **Access package leaks.** Controls: package built only from the caller's own rows, sealed with AES-GCM (AAD binds tenant and request), seven-day validity, three generations per day, `private, no-store`, fixed file name, digest header equal to the manifest, no email attachment.
6. **Notes and messages carry personal values.** Controls: internal notes are sealed and never returned (only a presence flag); employee messages are bounded text written by the platform; logs and audit events carry identifiers and fixed labels only (allow-listed keys).
7. **Replay and double submit.** Controls: idempotency keys on every write, `PRIVACY_IDEMPOTENCY_REUSED` for a reused key with different input, one open request per scope enforced by a unique index.
8. **Abuse and enumeration.** Controls: fixed-window quotas and subject buckets, strict body parsing with size limits, opaque references.
9. **Stolen platform token.** Control: exact MFA on every platform operation (`@PlatformScoped`); a purge also needs a preview owned by the same actor.
10. **Insider edits evidence in the database.** Controls: append-only triggers; evidence deletion only inside an executing purge with a matching guarded item; rollback script refuses while governance evidence exists. A superuser can still bypass triggers: database access is an operator control outside this story.
11. **Minimized lifecycle evidence still identifies the employee, partner or product (R227-1).** Controls: a minimize or erase deletes the events and nulls every identifying column of the referral header together (a CHECK forbids a partial scrub); the scrub and the deletion happen only inside an executing purge that holds a guarded lifecycle item, after every other class is gone, under an exact-MFA platform administrator, with a hold refusing it; the tombstone can never change again; every employee and platform read joins the references the tombstone no longer has, so none can return it; the residue allow-list admits only `event_count`, `opened_at` and `closed_at`. Residual: the immutable stamp keeps the country and the policy, and the employee's own request rows keep the pairing with the opaque reference.

## Residual risks

A lifecycle tombstone keeps a random reference, the tenant, the closed state, counters and the original timestamps; the employee's own privacy-request rows still pair the employee with the referral reference they asked about; rate-limit counters are per Core instance; the legal content of any policy is the operator's responsibility; no AWS verification was run for this story.
