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
