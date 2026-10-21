# ADR 0027 Grounded operational summary: a governed daily briefing by construction

## Status

Decisions D101-1 to D101-16 approved by the architect on Issue #156; implemented by the MVP-101 pull
request, pending architecture review of the implementation. Builds on ADR 0026 (operational status,
the only data source), ADR 0016 (neutral, factual wording), ADR 0002 (bilingual first class) and
`docs/AI-GOVERNANCE.md`.

## Context

MVP-101 is the first governed Dival AI capability. A leader asks for a short written briefing of the
figures MVP-100 already shows. The briefing must be correct in French and English, must not reveal
more than the screen it summarizes, must survive a failing, slow or hostile model, and must not make
the model an authority over any fact, name or conclusion.

## Decision

- **Two POST endpoints, Core only (D101-1, D101-4).** `POST /api/v1/operations/status-summary`
  (tenant administrator, exact MFA; optional `siteId` and `legalEntityId`) and
  `POST /api/v1/me/team-operations/status-summary` (manager through the active employee link). The
  body is `{"language": "en" | "fr"}` and nothing else (a strict request). The browser calls Core
  only; it never reaches the AI service or a provider.
- **On demand, nothing stored (D101-2).** No table, no migration, no stored briefing, prompt or
  response. `Cache-Control: private, no-store`.
- **Only aggregates cross the boundary (D101-3).** Counts, the fixed thresholds (5, 30, 48, 72), a
  closed list of candidate facts with opaque, per-request, randomly permuted identifiers (`f1`..),
  and the as-of instant. Never a name, identifier, free text, label, date other than the as-of
  instant, tenant, actor or scope identifier. `docs/security/MVP-101-AI-DATA-FLOW.md` lists every
  field.
- **Service authentication (D101-5).** Core obtains a Keycloak client-credentials token
  (`divalhr-core-ai`, audience `divalhr-ai-service`, capability role
  `generate-operational-summary`, lifetime 120 s, cached until 30 s before expiry). The AI service
  validates signature (RS256, JWKS), issuer, audience, authorized party and role in middleware before
  the body is read. Without issuer and JWKS configuration it answers 503: it never runs
  unauthenticated. Secrets are never committed, logged or returned.
- **Grounding by construction (D101-6).** The planner returns only a headline code and at most five
  supplied fact identifiers. Every visible sentence is rendered from reviewed bilingual templates
  (`ops-summary-1`) with a number and a threshold taken from the snapshot. No planner output is ever
  displayed. The deterministic policy (D101-9) is the oracle.
- **Provider-neutral gateway (D101-7, D101-8).** A deterministic planner (default) and an
  OpenAI-compatible planner (explicit configuration, https outside development). Four-second
  deadline, one attempt, no retry. Any provider failure, malformed or out-of-contract answer falls
  back to the deterministic planner and the briefing says `generationMode: FALLBACK`.
- **Priority policy (D101-9).** Not checked in, uncovered, overdue approvals, late, pending
  approvals, expected, normal status; larger severity band first, then the stable type order; at
  most five; every non-zero priority 1 to 3 fact is mandatory and cannot be omitted or demoted.
- **Core re-validates (D101-10).** Core rebuilds the expected facts from its own aggregates and
  rejects any answer whose language, mode, template version, headline, selection, fact binding,
  order, mandatory facts, rendered text, vocabulary, numbers, thresholds or limitations differ. A
  rejected answer is `503 OPERATIONAL_SUMMARY_UNAVAILABLE` without detail.
- **Audit and telemetry (D101-12).** `ai.operational-summary-admin` and
  `ai.operational-summary-team` are written fail-closed after validation, in their own transaction;
  Core never calls the AI service inside a database transaction. Metadata is bounded: schema
  version, language, scope kind, filter flags, generation mode, selected-fact count, severity bands,
  template version, plus a digest of the facts. Metrics and logs carry bounded tags only.
- **Evaluation and release gate (D101-13).** The shared set
  `packages/shared-contracts/evaluation/operational-summary/v1` is executed by vitest, pytest and
  Core JUnit with the same expectations, independently in French and English. A material change
  (candidate construction, policy, templates, prompt, output schema, provider, model) requires
  re-running it and architect review.
- **Web (D101-11).** A « Synthèse du jour » / "Daily briefing" panel on both operations pages:
  generated on request, in the display language, with the as-of instant, the generation mode (never
  a provider or model name), source links from an allow-listed route identifier, and the three
  limitation statements. Changing the language or the filters discards the briefing. Nothing is put
  in browser storage or a URL.

## Consequences

- A model can only choose and order facts Core already offered; it cannot introduce a number, name,
  claim, link or conclusion. Prompt injection has no free-text channel to travel through.
- The briefing is a function of the status at one instant; there is no history, no trend and no
  notification.
- Core depends on the AI service for this endpoint only; unavailability degrades one panel and
  never the status.
- The test environment (hr-dev) does not define the `divalhr-core-ai` client in this change; there
  the endpoints answer 503 until a separately approved change provisions it and the AI service.

## Not included

Stored briefings, history, free-text questions, retrieval, tool use, notifications, per-employee
content, any conclusion about leave, pay, absence or discipline, and any change to MVP-100.
