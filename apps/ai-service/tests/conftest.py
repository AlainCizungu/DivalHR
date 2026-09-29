from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from divalhr_ai.config import Settings
from divalhr_ai.main import create_app


@pytest.fixture
def settings() -> Settings:
    return Settings(
        environment="test", version="9.9.9", cors_allowed_origins=["http://localhost:5173"]
    )


@pytest.fixture
def client(settings: Settings) -> Iterator[TestClient]:
    with TestClient(create_app(settings)) as test_client:
        yield test_client
