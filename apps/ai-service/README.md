# DivalHR AI Service

Python 3.12 + FastAPI shell. Sprint 0 has **no model or provider integration**; see
`docs/AI-GOVERNANCE.md` before adding any AI capability.

The browser may call only `GET /api/v1/system/status`. Future AI operations enter through the Core
API boundary; this service is not a browser-facing backend.

## Native commands (no `make` required)

```bash
uv sync --frozen                         # install pinned dependencies
uv run ruff format --check . && uv run ruff check .
uv run mypy
uv run pytest
uv run uvicorn divalhr_ai.asgi:app --port 8090   # run locally
```

Configuration uses `DIVALHR_AI_*` environment variables (see `src/divalhr_ai/config.py`).

## Grounded operational summary (MVP-101, ADR 0027)

`POST /api/v1/internal/operational-summary` accepts only Core's service token (Keycloak client
`divalhr-core-ai`, audience `divalhr-ai-service`, role `generate-operational-summary`) and a closed
aggregate snapshot (`packages/shared-contracts/schemas/operational-summary-input.schema.json`). A
planner (deterministic by default, OpenAI-compatible when configured) returns only a headline code
and fact ids; reviewed bilingual templates write every sentence. Any provider problem falls back to
the deterministic planner (`generationMode: FALLBACK`).

| Variable | Purpose |
| --- | --- |
| `DIVALHR_AI_SERVICE_AUTH_ISSUER`, `DIVALHR_AI_SERVICE_AUTH_JWKS_URL` | Required for the internal operation; without them it answers 503 |
| `DIVALHR_AI_SUMMARY_PROVIDER_MODE` | `deterministic` (default) or `openai-compatible` |
| `DIVALHR_AI_SUMMARY_PROVIDER_BASE_URL`, `_MODEL`, `_API_KEY` | Required in model mode; https outside development; the key is never logged |
| `DIVALHR_AI_SUMMARY_PROVIDER_TIMEOUT_SECONDS` | Deadline, at most 4 s; one attempt |
