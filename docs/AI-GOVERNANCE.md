# DivalHR AI Governance

## Purpose

Dival AI helps authorized users understand and act on workforce information. It does not replace accountable decision makers.

## Allowed initial use cases

- Daily operational summaries
- Contract and credential expiration alerts
- Payroll-input variance explanations
- Receipt extraction and categorization suggestions
- Answers from approved HR policies
- Draft management reports

## Prohibited autonomous actions

AI may not autonomously:

- hire, fire, suspend, promote, or discipline
- change compensation
- approve payroll
- approve or price a loan
- disclose employee information to a lender
- send legally consequential messages
- bypass approval or segregation-of-duties controls

## Architecture

- Model gateway chooses approved models by task, sensitivity, language, cost, latency, and residency.
- Retrieval uses tenant-filtered, authorization-filtered sources.
- Tools expose narrow capabilities and independently authorize every call.
- Outputs include the relevant period, population, and source links.
- AI activity produces audit and evaluation records.

## Bilingual quality

Every production AI task is evaluated independently in French and English. Passing in one language does not approve the other.

## Evaluation dimensions

- Factual grounding
- Citation correctness
- Authorization boundaries
- French and English task quality
- Local terminology
- Bias and disparate impact
- Refusal behavior
- Prompt-injection resistance
- Tool-selection correctness
- Human escalation
- Cost and latency

## Release rule

A task is enabled only for the user population and data scope covered by its evaluation. Material prompt, model, retrieval, or tool changes trigger re-evaluation.

## Finance rule

AI may help a regulated partner review information, but DivalHR will not present an AI output as the lending decision. The partner remains responsible for underwriting, explainability, fairness, and regulatory compliance.
