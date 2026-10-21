import pytest
from pydantic import ValidationError

from divalhr_ai.config import Settings
from divalhr_ai.summary.gateway import OpenAICompatiblePlanner, build_model_planner

KEY = "a-long-enough-provider-key"


def make(**overrides: object) -> Settings:
    base: dict[str, object] = {"environment": "production"}
    base.update(overrides)
    return Settings(**base)  # type: ignore[arg-type]


def test_default_mode_is_deterministic_and_builds_no_planner() -> None:
    settings = make()
    assert settings.summary_provider_mode == "deterministic"
    assert build_model_planner(settings) is None


def test_model_mode_requires_https_model_and_key() -> None:
    good = {
        "summary_provider_mode": "openai-compatible",
        "summary_provider_base_url": "https://provider.example.test/v1",
        "summary_provider_model": "m-1",
        "summary_provider_api_key": KEY,
    }
    assert isinstance(build_model_planner(make(**good)), OpenAICompatiblePlanner)
    for override in (
        {"summary_provider_base_url": "http://provider.example.test/v1"},
        {"summary_provider_base_url": None},
        {"summary_provider_model": " "},
        {"summary_provider_model": None},
        {"summary_provider_api_key": "short"},
        {"summary_provider_api_key": None},
        {"summary_provider_api_key": "dev-only-" + KEY},
    ):
        with pytest.raises(ValidationError):
            make(**{**good, **override})
    # development may use http and a dev key
    make(
        **{
            **good,
            "environment": "development",
            "summary_provider_base_url": "http://localhost:9/v1",
            "summary_provider_api_key": "dev-only-" + KEY,
        }
    )


def test_timeout_is_bounded_and_secret_is_masked() -> None:
    with pytest.raises(ValidationError):
        make(summary_provider_timeout_seconds=5.0)
    settings = make(summary_provider_api_key=KEY)
    assert KEY not in repr(settings)
    assert KEY not in str(settings.model_dump())


def test_service_auth_urls_must_be_https_outside_development() -> None:
    with pytest.raises(ValidationError):
        make(
            service_auth_issuer="http://kc.example.test/realms/x",
            service_auth_jwks_url="https://kc.example.test/certs",
        )
    make(
        service_auth_issuer="https://kc.example.test/realms/x",
        service_auth_jwks_url="https://kc.example.test/certs",
    )
