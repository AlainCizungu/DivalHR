"""Fails when the implementation drifts from the design-first AI Service contract."""

from pathlib import Path
from typing import Any

import yaml

from divalhr_ai.config import Settings
from divalhr_ai.main import create_app

CONTRACT = Path(__file__).resolve().parents[3] / "packages/shared-contracts/openapi/ai-service.yaml"
BASE = "/api/v1"
METHODS = {"get", "put", "post", "delete", "patch"}


def _operations(doc: dict[str, Any], strip: bool, only_implemented: bool) -> dict[str, Any]:
    result = {}
    for path, item in doc.get("paths", {}).items():
        key_path = path[len(BASE) :] if strip and path.startswith(BASE) else path
        for method, op in item.items():
            if method not in METHODS:
                continue
            if only_implemented and op.get("x-divalhr-lifecycle") != "implemented":
                continue
            result[f"{method.upper()} {key_path}"] = op
    return result


def _resolve(doc: dict[str, Any], node: dict[str, Any]) -> dict[str, Any]:
    while "$ref" in node:
        target: Any = doc
        for part in node["$ref"][2:].split("/"):
            target = target[part]
        node = target
    return node


def _props(doc: dict[str, Any], op: dict[str, Any], code: str) -> set[str]:
    content = op["responses"][code].get("content", {})
    return {
        prop
        for media in content.values()
        for prop in _resolve(doc, media["schema"]).get("properties", {})
    }


def test_implementation_matches_contract() -> None:
    contract = yaml.safe_load(CONTRACT.read_text(encoding="utf-8"))
    generated = create_app(Settings(environment="test")).openapi()
    expected = _operations(contract, strip=False, only_implemented=True)
    actual = _operations(generated, strip=True, only_implemented=False)
    assert set(actual) == set(expected)
    for key, op in expected.items():
        assert actual[key]["operationId"] == op["operationId"]
        codes = {c for c in op["responses"] if c.startswith(("2", "5"))}
        assert codes <= set(actual[key]["responses"])
        for code in codes:
            assert _props(generated, actual[key], code) == _props(contract, op, code)
