# DivalHR Security and Privacy Baseline

## Principles

- Deny by default.
- Enforce least privilege.
- Authenticate the actor and authorize the action at every boundary.
- Treat tenant context as verified security state.
- Minimize collection and disclosure.
- Require traceability for sensitive actions.
- Keep secrets out of source code, logs, analytics, and clients.

## Authentication

- OIDC for workforce authentication
- SAML support for enterprise customers
- MFA required for privileged roles
- Short-lived access tokens
- Secure recovery and session revocation
- Separate service credentials with scoped permissions

## Authorization

Use role-based access for understandable administration and attribute-based rules for sensitive conditions.

Relevant attributes include tenant, legal entity, site, department, manager relationship, worker relationship, data category, action, and elevated privilege.

## Data classification

- Public
- Internal
- Confidential
- Restricted HR
- Payroll and banking
- Financial services
- Authentication secret
- AI interaction data

## Audit

Sensitive events include actor, action, tenant, object, result, time, reason, correlation ID, and an integrity-protected reference to relevant before-and-after values.

## Application security

- Threat modeling
- Peer review
- Dependency and container scanning
- Static and dynamic testing
- Infrastructure policy validation
- Secret scanning
- Tenant-isolation tests
- Penetration testing before enterprise or Finance launch
- Document malware scanning

## Privacy

- Purpose limitation
- Data minimization
- Consent and disclosure records
- Retention and deletion schedules
- Correction and export workflows
- Incident and breach response
- Vendor and partner assessment
- Country-specific legal review before launch

## Financial controls

DivalHR does not make lending decisions. Every disclosure requires a named partner, purpose, approved data set, consent record, expiration, and audit trail.

## AI controls

Authorization is enforced before retrieval. Tools are narrowly scoped. High-impact actions require human approval. Prompts and outputs follow separate retention and redaction rules.

## Production gates

- Threat model approved
- High-severity findings resolved
- Backup restore tested
- Tenant-isolation suite passing
- Privileged MFA enabled
- Secrets and key rotation tested
- Audit events verified
- Privacy notice and retention configuration approved
- Incident runbooks exercised
