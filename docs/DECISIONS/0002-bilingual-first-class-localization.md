# ADR 0002 Make French and English First Class

## Status

Accepted

## Context

DivalHR targets the DRC and surrounding African markets while serving organizations that may operate in French, English, or both.

## Decision

French and English receive equal feature coverage. Language preference is stored per organization and user. Translation keys remain independent of business identifiers. CI verifies translation parity.

## Consequences

Every feature carries localization work and testing. The product avoids a costly retrofit and can serve bilingual organizations credibly.
