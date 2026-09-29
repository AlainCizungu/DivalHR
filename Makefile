# Convenience wrapper. Every target maps to documented native commands (see README.md), so the
# project remains usable where make is unavailable.
SHELL := /bin/bash
COMPOSE := docker compose -f infrastructure/docker/compose.yaml --env-file $(or $(wildcard .env),.env.example)
PNPM := pnpm

.PHONY: help install up down logs ps test lint build format i18n-check api-check e2e e2e-host clean

help: ## List targets
	@grep -E '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "} {printf "  %-12s %s\n", $$1, $$2}'

install: ## Install JavaScript and Python dependencies
	$(PNPM) install --frozen-lockfile
	cd apps/ai-service && uv sync --frozen

up: ## Build and start the full stack; waits until every service is healthy
	$(COMPOSE) up -d --build --wait --wait-timeout 300

down: ## Stop the stack and delete its volumes
	$(COMPOSE) down -v

logs: ## Follow stack logs
	$(COMPOSE) logs -f

ps: ## Show service health
	$(COMPOSE) ps

lint: ## Format checks, linters, type checks, translation parity and contract lint
	$(PNPM) format:check
	$(PNPM) lint
	$(PNPM) typecheck
	$(PNPM) api:lint
	$(PNPM) api:check
	cd apps/ai-service && uv run ruff format --check . && uv run ruff check . && uv run mypy
	cd apps/core-api && ./gradlew --no-daemon spotlessCheck spotbugsMain

test: ## Unit, integration, contract and architecture tests for every deployable
	$(PNPM) test
	cd apps/ai-service && uv run pytest
	cd apps/core-api && ./gradlew --no-daemon test

build: ## Build every deployable
	$(PNPM) build
	cd apps/core-api && ./gradlew --no-daemon bootJar
	$(COMPOSE) build

format: ## Apply formatters
	$(PNPM) format
	cd apps/ai-service && uv run ruff format . && uv run ruff check --fix .
	cd apps/core-api && ./gradlew --no-daemon spotlessApply

i18n-check: ## Fail if French and English keys differ
	$(PNPM) i18n:check

api-check: ## Lint contracts and verify the generated client is current
	$(PNPM) api:lint
	$(PNPM) api:check

e2e: up e2e-host ## Start the stack and run the Playwright smoke tests

e2e-host: ## Run Playwright smoke tests against an already running stack
	cd apps/web && $(PNPM) exec playwright install chromium && $(PNPM) exec playwright test

clean: ## Remove build output
	rm -rf apps/web/dist apps/core-api/build
