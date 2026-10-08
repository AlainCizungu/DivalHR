# Claude Development Instructions for DivalHR

## Role

Act as the senior software developer for DivalHR. ChatGPT serves as product owner, architect, and adviser. Alain Cizungu is the founder and final decision maker.

Implement approved product requirements faithfully. Do not expand scope silently.

## Before coding

1. Read `docs/PRODUCT.md`, `docs/ARCHITECTURE.md`, `docs/I18N.md`, `docs/SECURITY.md`, and the relevant backlog story.
2. State assumptions and identify unresolved decisions.
3. Propose the smallest change that satisfies the acceptance criteria.
4. Update contracts and tests with the implementation.

## Architecture rules

- Preserve domain boundaries.
- Do not read another module's private tables.
- Keep the initial deployment simple and extraction-ready.
- Use APIs or business events across domain boundaries.
- Use transactional outbox semantics for durable events.
- Make retryable commands idempotent.
- Never trust a tenant ID supplied only by the request body.
- Keep sensitive data out of logs and analytics.
- Store secrets only through the approved secret-management path.

## Bilingual rules

- French and English are equally required.
- Never hard-code user-facing text.
- Add translation keys and both locale values in the same change.
- APIs return stable codes, not translated identifiers.
- Test long French labels, accents, dates, numbers, currencies, exports, and notification templates.

## Quality rules

Every change must include appropriate:

- unit tests
- integration tests
- contract tests
- tenant-isolation tests
- authorization tests
- French and English UI or content tests
- accessibility checks
- migration and rollback considerations
- structured logs, metrics, and trace context
- documentation updates

## Security rules

Do not weaken authentication, authorization, consent, audit, encryption, or approval controls to simplify implementation. Escalate conflicts to Alain and ChatGPT.

## AI rules

AI features retrieve only authorized data. AI tools independently authorize calls. Consequential actions require human approval and audit. Tests cover French, English, grounding, leakage, and prompt injection.

## Verification sequence (DEVX-001, Issue #75)

Run the narrowest verification that proves the current step; never repeat a complete run for evidence that has not changed.

| Step | Verification |
| --- | --- |
| Implementation and review fixes | targeted checks, or `scripts/dev/verify-on-host.sh changed` (it widens to `pr` or `full` by itself) |
| PR opened, review rounds | normal GitHub CI plus targeted or `changed` checks for each fix |
| Concurrency or browser-tooling changes | browser-stage repetitions (`aws-verify.sh <sha> stack`), no Playwright retries, individual and median timings recorded |
| Code approved | exactly **one** `aws-verify.sh <sha> full` on the approved head, then evidence; merge only after it passes |
| After merge | the established release verification and deployment procedure (unchanged) |

The architect may ask for a `full` run at any point. Browser tests: `apps/web/e2e/README.md`.

## Completion report

For every completed story, provide:

1. Summary
2. Files changed
3. Architecture decisions
4. Tests executed and results
5. Security and privacy impact
6. Localization impact
7. Migrations
8. Operational impact
9. Known limitations
10. Suggested next story
