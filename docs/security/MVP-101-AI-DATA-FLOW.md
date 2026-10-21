# MVP-101 AI data flow and threat model

Scope: `POST /api/v1/operations/status-summary` and `POST /api/v1/me/team-operations/status-summary`
(ADR 0027). Restricted HR/Operations disclosure.

## Path

Browser -> Core API (user token, language only) -> Core builds aggregates from the same data as the
operational status -> Core -> AI service (service token, closed snapshot) -> optional model provider
(closed snapshot, same fields) -> AI service renders reviewed templates -> Core validates -> browser.

## Exact fields that cross the AI boundary (schema `operational-summary-input`)

| Field | Content |
| --- | --- |
| `schemaVersion` | constant |
| `language` | `en` or `fr` |
| `asOfInstant` | the server instant of the status |
| `scope.kind` | `ORGANIZATION` or `TEAM` |
| `scope.siteFilter`, `scope.legalEntityFilter` | booleans: whether a filter was applied, never which |
| `thresholds` | 5, 30, 48, 72 (minutes, minutes, hours, hours) |
| `shifts` | six counts of shift occurrences |
| `coverage` | uncovered count, or null for a team |
| `approvals` | pending and overdue counts for leave and timesheets |
| `facts[]` | opaque id (`f1`.., random per request), closed type, count, threshold, severity band, route identifier |

Never sent: employee, manager or caller names, identifiers or numbers, tenant, site or legal-entity
identifiers or names, slot labels, request or timesheet identifiers, free text, any date other than
the as-of instant, the caller's token, session or address.

## What returns

`language`, `generationMode`, `templateVersion`, a headline code and text, one to five highlights
(fact id, type, severity, route identifier, text) and three limitation statements. Core re-derives
all of it from its own aggregates; nothing from the AI service is displayed unvalidated, and no
model text is displayed at all.

## Threats and controls

| Threat | Control |
| --- | --- |
| Personal data reaches a model | Closed schema, strict parsing on both sides, no string field except fixed enums and ids; privacy test asserts the exact serialized body |
| Prompt injection | No free text exists to inject through; the planner output is a code and ids; invalid-input set contains injection-looking enum values |
| Hallucinated or altered figures | Text is rendered by templates from the snapshot; Core compares number, threshold, text and vocabulary |
| Suppressing a critical fact | Mandatory priority 1 to 3 facts must be selected and ordered first |
| Unauthorized caller of the AI service | Keycloak client-credentials token validated before the body is read; wrong audience, azp, role, issuer, expiry or algorithm is 401/403 |
| Replay of the id mapping | Identifiers are per-request random; nothing is stored |
| Provider outage, latency or malformed answer | 4 s deadline, one attempt, deterministic fallback |
| Disclosure without audit | Audit is fail-closed: no audit, no briefing |
| Secret exposure | Secrets only from configuration; redacted `toString`; `dev-only-` secrets refused outside development; never logged or returned |
| Browser reaches the AI service | The browser has no route, token or URL for it |
