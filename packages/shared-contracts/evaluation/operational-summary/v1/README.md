# Operational summary evaluation set (MVP-101, D101-13)

Version `operational-summary-eval-v1`. Synthetic aggregate snapshots only: no name, identifier, label
or free text exists anywhere in these files.

| File | Purpose |
| --- | --- |
| `cases.json` | Twelve valid snapshots (the request language is set by the test) with the oracle: headline code, ordered fact ids and the mandatory (priority 1 to 3) fact ids. |
| `invalid-inputs.json` | Twenty-two patches that must be rejected before any planner or model runs (schema layer and semantic layer, including prompt-injection-looking strings in enum positions). |
| `plans.json` | Candidate planner outputs for the over-the-cap case: valid ones are accepted, every invalid one is rejected and answered by the deterministic planner. |
| `prohibited-vocabulary.json` | Stems that must never appear in a rendered headline or highlight, English and French. |
| `expected-text.json` | The exact reviewed English and French rendering of every case (templates `ops-summary-1`). |
| `pluralization.json` | The exact reviewed rendering of every fact type at the values 0, 1, 2 and 5, English and French. |

The deterministic priority policy (D101-9) is the oracle: priority, then larger severity band, then
the stable fact-type order, at most five facts, every non-zero priority 1 to 3 fact always selected.
Provider failure classes (timeout, rate limit, transport, malformed JSON, refusal, circuit open) are
exercised with mocked HTTP in `apps/ai-service/tests`, not here.

Any material change to candidate construction, the priority policy, templates, the model prompt, the
output schema, a provider adapter or the model identifier requires this set to be re-run and, when
the oracle legitimately changes, re-reviewed by the architect.
