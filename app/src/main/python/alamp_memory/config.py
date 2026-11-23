"""Чтение конфигурации сервисов из services.json (секция embedding_services).

Формат services.json (рядом с БД):

    {
      "embedding_services": [
        {
          "url": "http://192.168.1.100:8788",     // базовый URL (без /v1/embeddings)
          "api_key": "",                           // опционально
          "model": "BAAI/bge-m3",                  // опционально
          "timeout": 0.5,                          // опционально (default: auto)
          "indexing": true                         // опционально (default: true)
        }
      ]
    }

Порядок в массиве = приоритет. Первый доступный используется.
"""

from __future__ import annotations

import json
import os
from typing import Any


def _find_services(db_path: str) -> str | None:
    """Ищет alamp_config.json рядом с БД."""
    db_dir = os.path.dirname(os.path.abspath(db_path))
    candidates = [
        os.path.join(db_dir, "alamp_config.json"),
        os.path.join(os.path.dirname(db_dir), "alamp_config.json"),
    ]
    for path in candidates:
        if os.path.exists(path):
            return path
    return None


def load_services(db_path: str) -> list[dict[str, Any]]:
    """Загружает список сервисов эмбеддинга из services.json.

    Returns:
        Список сервисов (может быть пустым, если файла нет).
    """
    path = _find_services(db_path)
    if not path:
        return []
    try:
        with open(path) as f:
            cfg = json.load(f)
        return cfg.get("embedding_services", [])
    except Exception:
        return []
