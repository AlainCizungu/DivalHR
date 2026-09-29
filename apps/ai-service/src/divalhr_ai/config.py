"""Configuration from environment variables (prefix ``DIVALHR_AI_``). No secrets have defaults."""

from functools import lru_cache
from typing import Annotated, Literal

from pydantic import Field, field_validator
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


@lru_cache
def get_settings() -> Settings:
    """Return cached settings."""
    return Settings()
