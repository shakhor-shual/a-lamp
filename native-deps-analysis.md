# Полный анализ нативных зависимостей nanobot v0.4.1 для Android

> Обновлено 2026-11-20. Проверено в чистом venv (87 пакетов).
> Venv: `/tmp/nb_venv` (создан через `~/.local/bin/virtualenv`)

---

## 1. Нативные пакеты — есть в Chaquopy-индексе (4)

Устанавливаются автоматически, сборка не требуется.

| Пакет | Язык | Зачем |
|-------|------|-------|
| `aiohttp` | C | HTTP-клиент/сервер |
| `lxml` | C | Парсинг HTML/XML |
| `pillow` | C | Обработка изображений |
| `pyyaml` | C | YAML-парсер |

---

## 2. Нативные пакеты — нужно собрать вручную (7)

### Rust (2)

| Пакет | Статус | Зачем |
|-------|--------|-------|
| `pydantic-core` | ✅ Собран (v2.41.1) | Ядро pydantic |
| `jiter` | ❌ | Через openai (JSON-парсинг) |

### C (5)

| Пакет | Статус | Зачем |
|-------|--------|-------|
| `yarl` | ❌ | URL-парсер (через aiohttp) |
| `websockets` | ❌ | WebSocket-клиент |
| `dulwich` | ❌ | Git-операции |
| `frozenlist` | ❌ | Замороженные списки (через aiohttp) |
| `multidict` | ❌ | Мульти-словари (через aiohttp/yarl) |
| `propcache` | ❌ | Кэширование свойств (через aiohttp/yarl) |

---

## 3. Исключены из Android-сборки (8)

| Пакет | Язык | Причина |
|-------|------|---------|
| `cryptography` | Rust | Не нужен без RemoteInstances |
| `sigstore` | Python | Не нужен без updater |
| `setproctitle` | C | Не нужен на Android |
| `tiktoken` | Rust | Lazy import (fallback на byte-оценки) |
| `watchfiles` | Rust | Lazy import (не нужно на Android) |
| `rapidfuzz` | C++ | Заменён на difflib (stdlib) |
| `anthropic` | Python | Lazy import (только Claude) |
| `ddgs` | Python | Lazy import (DuckDuckGo search) |

---

## 4. Чистый Python — не требуют сборки (все остальные)

typing_extensions, wcwidth и остальные ~70 пакетов — чистый Python.
Устанавливаются с PyPI без проблем.

---

## 5. Итого к сборке

**7 пакетов:** 2 Rust + 5 C.

Порядок сборки (по сложности):
1. `multidict` (C) — самый простой, чистый C
2. `frozenlist` (C) — простой C
3. `propcache` (C) — простой C
4. `yarl` (C) — зависит от multidict
5. `websockets` (C) — без зависимостей
6. `dulwich` (C) — зависит от urllib3 (pure)
7. `jiter` (Rust) — через openai
