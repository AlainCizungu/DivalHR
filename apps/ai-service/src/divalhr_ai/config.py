"""Configuration from environment variables (prefix ``DIVALHR_AI_``). No secrets have defaults."""

from functools import lru_cache
from typing import Annotated, Literal
from urllib.parse import urlsplit

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime settings for the AI Service."""

    model_config = SettingsConfigDict(env_prefix="DIVALHR_AI_", extra="ignore")

    environment: Literal["development", "test", "staging", "production"] = "development"
    version: str = "0.1.0"
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"
    # Comma-separated in the environment; NoDecode stops JSON parsing of the raw value.
    cors_allowed_origins: Annotated[list[str], NoDecode] = Field(
        default_factory=lambda: ["http://localhost:5173"]
    )
    openapi_enabled: bool = False

    # MVP-101 (D101-7): the model gateway. Deterministic by default; a model planner is enabled
    # only by explicit configuration. No secret has a default.
    summary_provider_mode: Literal["deterministic", "openai-compatible"] = "deterministic"
    summary_provider_base_url: str | None = None
    summary_provider_model: str | None = None
    summary_provider_api_key: SecretStr | None = None
    summary_provider_timeout_seconds: float = Field(default=4.0, gt=0, le=4.0)
    summary_provider_max_output_tokens: int = Field(default=300, ge=16, le=1000)

    # MVP-101 (D101-5): service-to-service authentication of the internal operation. Without an
    # issuer and a JWKS location the internal operation answers 503: it never runs unauthenticated.
    service_auth_issuer: str | None = None
    service_auth_jwks_url: str | None = None
    service_auth_audience: str = "divalhr-ai-service"
    service_auth_client_id: str = "divalhr-core-ai"
    service_auth_capability: str = "generate-operational-summary"

    @field_validator("cors_allowed_origins", mode="before")
    @classmethod
    def _split_origins(cls, value: object) -> object:
        if isinstance(value, str):
            return [origin.strip() for origin in value.split(",") if origin.strip()]
        return value

    @field_validator("cors_allowed_origins")
    @classmethod
    def _reject_wildcards(cls, value: list[str]) -> list[str]:
        for origin in value:
            if "*" in origin:
                raise ValueError(f"Wildcard CORS origins are not permitted: {origin}")
        return value

    @model_validator(mode="after")
    def _validate_summary(self) -> "Settings":
        relaxed = self.environment in ("development", "test")
        if self.summary_provider_mode == "openai-compatible":
            url = urlsplit(self.summary_provider_base_url or "")
            if not url.hostname or url.scheme not in (("https", "http") if relaxed else ("https",)):
                raise ValueError("summary_provider_base_url must be an approved https URL")
            model = (self.summary_provider_model or "").strip()
            if not model or len(model) > 100 or any(c.isspace() for c in model):
                raise ValueError("summary_provider_model is required in model mode")
            key = self.summary_provider_api_key
            if key is None or len(key.get_secret_value()) < 16:
                raise ValueError("summary_provider_api_key is required in model mode")
            if not relaxed and key.get_secret_value().lower().startswith("dev-only-"):
                raise ValueError("a development provider key must not be used here")
        for name in ("service_auth_issuer", "service_auth_jwks_url"):
            value = getattr(self, name)
            if value is not None:
                url = urlsplit(value)
                if not url.hostname or url.scheme not in (
                    ("https", "http") if relaxed else ("https",)
                ):
                    raise ValueError(f"{name} must be https outside development and test")
        return self


@lru_cache
def get_settings() -> Settings:
    """Return cached settings."""
    return Settings()
