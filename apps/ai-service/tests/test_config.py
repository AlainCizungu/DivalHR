import pytest
from pydantic import ValidationError

from divalhr_ai.config import Settings, get_settings


def test_rejects_wildcard_origins() -> None:
    with pytest.raises(ValidationError):
        Settings(cors_allowed_origins=["*"])


def test_splits_comma_separated_origins(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DIVALHR_AI_CORS_ALLOWED_ORIGINS", "http://a.test, http://b.test")
    get_settings.cache_clear()
    try:
        assert get_settings().cors_allowed_origins == ["http://a.test", "http://b.test"]
    finally:
        get_settings.cache_clear()
