# Android-сборка nanobot: точные версии зависимостей

> Зафиксировано 2026-11-20. Все версии проверены на хосте (Ubuntu x86_64).

## Нативные пакеты (требуют кросс-компиляции)

| Пакет | Версия | Язык | Статус |
|-------|--------|------|--------|
| `pydantic-core` | 2.41.1 | Rust | ✅ Собран вручную, wheel готов |
| `lxml` | 5.3.0 | C | ✅ Есть в Chaquopy-индексе |
| `lxml-html-clean` | 0.4.6 | Python | ✅ Чистый Python |

## Ключевые зависимости (чистый Python)

| Пакет | Версия | Примечание |
|-------|--------|-----------|
| `pydantic` | 2.12.0 | Требует pydantic-core==2.41.1 |
| `pydantic-settings` | 2.16.3 | |
| `httpx[socks]` | 0.28.1 | |
| `aiohttp` | 3.14.3 | |
| `yarl` | 1.24.5 | |
| `websockets` | 17.4 | |
| `typer` | 0.29.2 | |
| `loguru` | 0.8.0 | |
| `Jinja2` | 3.1.2 | |
| `PyYAML` | 6.0.1 | |
| `rich` | 15.0.0 | |
| `markdown-it-py` | 3.0.0 | |
| `mcp` | 2.3.0 | |
| `openai` | 3.57.0 | |
| `pillow` | 13.0.0 | |
| `qrcode[pil]` | 8.2 | |
| `croniter` | 7.0.0 | |
| `prompt-toolkit` | 3.0.53 | |
| `questionary` | 2.1.1 | |
| `chardet` | 5.2.0 | |
| `packaging` | 26.3 | |
| `tzdata` | 2026.6 | |
| `tzlocal` | 5.4.5 | |
| `defusedxml` | 0.7.1 | |
| `pypdf` | 7.1.1 | |
| `python-docx` | 1.2.0 | |
| `openpyxl` | 3.1.5 | |
| `python-pptx` | 1.0.2 | |
| `filelock` | 4.0.1 | |
| `dulwich` | 1.2.21 | |
| `readability-lxml` | 0.9 | |
| `json-repair` | 0.57.0 | |
| `oauth-cli-kit` | 0.1.6 | |
| `aiohttp-socks` | 0.12.0 | |

## Исключены из Android-сборки

| Пакет | Причина |
|-------|---------|
| `cryptography` | Не нужен без RemoteInstances |
| `sigstore` | Не нужен без updater |
| `setproctitle` | Не нужен на Android |
| `tiktoken` | Заменён на byte-оценки |
| `watchfiles` | Не нужен на Android |
| `anthropic` | Только для Claude (lazy import) |
| `ddgs` | DuckDuckGo search (lazy import) |
| `rapidfuzz` | Заменён на difflib (stdlib) |
| `primp` | Не нужен (HTTPX-based) |

## Стратегия сборки

1. **pydantic-core** — установить из локального wheel (`app/pydantic_core-2.41.5-cp312-abi3-android_24_arm64_v8a.whl`)
2. **lxml** — берётся из Chaquopy-индекса (версия 5.3.0)
3. **Всё остальное** — чистый Python, берётся с PyPI
4. **METADATA** в wheel nanobot — указать все версии с `==` для исключения backtracking
