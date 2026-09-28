# DivalHR AI Service image. Base images are pinned by version and digest.
FROM python:3.12.14-slim-trixie@sha256:f77ac9e44ae96ef2c90b8053ea08c31f8be030f824196b0ae4db6d462c84e51f AS build
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_CACHE_DIR=1 UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy
RUN pip install uv==0.12.20
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY src ./src
COPY README.md ./
RUN uv sync --frozen --no-dev

FROM python:3.12.14-slim-trixie@sha256:f77ac9e44ae96ef2c90b8053ea08c31f8be030f824196b0ae4db6d462c84e51f
RUN groupadd --system --gid 10001 divalhr && useradd --system --uid 10001 --gid divalhr --no-create-home divalhr
WORKDIR /app
COPY --from=build --chown=divalhr:divalhr /app /app
ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1
USER 10001
EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=6 \
  CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8090/health/ready', timeout=2).status == 200 else 1)"]
CMD ["uvicorn", "divalhr_ai.asgi:app", "--host", "0.0.0.0", "--port", "8090", "--no-access-log", "--proxy-headers", "--forwarded-allow-ips", "127.0.0.1"]
