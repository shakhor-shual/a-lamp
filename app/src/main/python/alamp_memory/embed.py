"""Получение эмбеддинга через bge-m3 (массив сервисов из alamp_config.json).

Порядок:
  1. Загружает список сервисов из alamp_config.json (секция embedding_services)
  2. Проходит по массиву по порядку (первый доступный = приоритетный)
  3. Для каждого сервиса определяет таймаут:
     - localhost / частный IP → 0.5с
     - внешний → 3с (или из поля timeout в конфиге)
  4. Если сервис недоступен — переходит к следующему
"""

from __future__ import annotations

import os
from urllib.parse import urlparse

import httpx

from .config import load_services

EMBED_MODEL = "BAAI/bge-m3"
EXPECTED_DIMS = 1024


def _connect_timeout(url: str, override: float | None = None) -> float:
    """Определяет таймаут подключения по IP.

    - localhost / частный IP → 0.5с
    - внешний домен → 3с
    - override из конфига → берём его
    """
    if override is not None:
        return override
    host = urlparse(url).hostname or ""
    if host in ("localhost", "127.0.0.1", "::1"):
        return 0.5
    try:
        import ipaddress
        ip = ipaddress.ip_address(host)
        return 0.5 if ip.is_private else 3.0
    except ValueError:
        return 3.0  # домен — внешний


async def embed_text(
    text: str,
    api_key: str = "",
    db_path: str = "",
    for_indexing: bool = False,
) -> list[float]:
    """Получает эмбеддинг текста через первый доступный сервис.

    Args:
        text: Текст для эмбеддинга.
        api_key: Запасной ключ OpenRouter (если нет в конфиге).
        db_path: Путь к БД (для поиска alamp_config.json).
        for_indexing: Если True — используются только сервисы с indexing=true.

    Returns:
        Список из 1024 float.

    Raises:
        RuntimeError: Если ни один сервис не доступен.
    """
    services = load_services(db_path)

    # Если конфига нет — fallback на старый способ (env vars)
    if not services:
        return await _embed_fallback(text, api_key)

    # Фильтруем по indexing, если нужно
    candidates = [
        s for s in services
        if not for_indexing or s.get("indexing", True)
    ]
    if not candidates:
        candidates = services  # если все отфильтровались — берём все

    last_error = None
    for svc in candidates:
        base = svc["url"].rstrip("/")
        # OpenRouter использует /api/v1/embeddings, локальные сервисы — /v1/embeddings
        if "openrouter.ai" in base:
            url = base + "/api/v1/embeddings"
        else:
            url = base + "/v1/embeddings"
        model = svc.get("model", EMBED_MODEL)
        token = svc.get("api_key", "") or api_key
        timeout_override = svc.get("timeout")
        connect_to = _connect_timeout(svc["url"], timeout_override)

        headers = {}
        if token:
            headers["Authorization"] = f"Bearer {token}"

        # SSL-сертификаты (на Android Chaquopy — из env)
        ssl_verify: str | bool = os.environ.get("SSL_CERT_FILE", "")
        if not ssl_verify or not os.path.exists(ssl_verify):
            ssl_verify = True

        try:
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(connect_to + 2.0, connect=connect_to),
                verify=ssl_verify,
            ) as client:
                resp = await client.post(
                    url,
                    json={"input": text, "model": model},
                    headers=headers,
                )
                resp.raise_for_status()
                data = resp.json()

            emb = data["data"][0]["embedding"]
            if len(emb) != EXPECTED_DIMS:
                raise ValueError(
                    f"Неверная размерность: {len(emb)}, ожидалось {EXPECTED_DIMS}"
                )
            return emb

        except Exception as exc:
            last_error = exc
            continue

    raise RuntimeError(
        f"Все сервисы эмбеддинга недоступны. Последняя ошибка: {last_error}"
    )


async def _embed_fallback(text: str, api_key: str = "") -> list[float]:
    """Старый fallback: LOCAL_EMBED_URL или OpenRouter из env."""
    local_url = os.environ.get("LOCAL_EMBED_URL", "")
    if local_url:
        url = local_url.rstrip("/") + "/v1/embeddings"
        headers = {}
    else:
        url = "https://openrouter.ai/api/v1/embeddings"
        token = os.environ.get("OPENROUTER_API_KEY", "") or api_key
        headers = {}
        if token:
            headers["Authorization"] = f"Bearer {token}"

    ssl_verify: str | bool = os.environ.get("SSL_CERT_FILE", "")
    if not ssl_verify or not os.path.exists(ssl_verify):
        ssl_verify = True

    async with httpx.AsyncClient(timeout=10, verify=ssl_verify) as client:
        resp = await client.post(
            url,
            json={"input": text, "model": EMBED_MODEL},
            headers=headers,
        )
        resp.raise_for_status()
        data = resp.json()

    emb = data["data"][0]["embedding"]
    if len(emb) != EXPECTED_DIMS:
        raise ValueError(
            f"Неверная размерность эмбеддинга: "
            f"ожидалось {EXPECTED_DIMS}, получено {len(emb)}"
        )
    return emb
