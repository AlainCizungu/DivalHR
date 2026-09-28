# ADR 0001 Start with Modular Microservice Ready Deployables

## Status

Accepted

## Context

DivalHR is intended to become a large platform, but it begins with one founder using AI development assistance. Numerous independent services would create disproportionate deployment, observability, testing, and incident-response cost.

## Decision

Begin with Web/PWA, Core API, and AI/Workers deployables. Preserve explicit domain modules, private data ownership, APIs, events, and extraction-ready boundaries.

## Consequences

Delivery and operations remain manageable. Some modules share a process initially. Extraction requires planned work later, but boundaries prevent uncontrolled coupling.
