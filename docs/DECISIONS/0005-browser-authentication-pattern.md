# ADR 0005 Browser authentication pattern for real HR data

## Status

Proposed. Required before any real employee data or pilot (Issue #3 review, decision 2).

## Context

Sprint 0 uses a public OIDC client with Authorization Code + PKCE in the browser, tokens held in
memory. This is approved for development only. Production handles restricted HR, payroll and
financial-services data (docs/SECURITY.md) and must resist XSS-driven token theft.

## Options

1. **Backend-for-frontend (BFF)**: a server-side confidential client; the browser holds only an
   HTTP-only, `SameSite=Strict` session cookie; tokens never reach JavaScript. Adds a deployable
   (or a Core API edge module), CSRF protection and session storage.
2. **Hardened SPA**: keep the public client with DPoP-bound or very short-lived tokens, refresh
   token rotation, strict CSP and Trusted Types. No new deployable, but tokens remain reachable
   by injected script.

## Decision

To be made by Alain Cizungu with ChatGPT (architect) before Phase 2 pilot data is loaded.

## Consequences

Until decided, no environment containing real personal data may use the Sprint 0 browser client.
