"""ASGI entry point: ``uvicorn divalhr_ai.asgi:app``."""

from divalhr_ai.main import create_app

app = create_app()
