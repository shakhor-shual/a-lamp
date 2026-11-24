# Структура SQLite БД nanobot 0.4.1 (Android)

> Файл: `app/data/sessions.sqlite3`
> Актуально на: 2026-11-24

## Таблицы nanobot (стандартные)

Создаются автоматически в `nanobot/session/sqlite_store.py`.

| Таблица | Назначение |
|---|---|
| `sessions` | Сессии |
| `messages` | Сообщения (JSON в `record`) |
| `checkpoints` | Контрольные точки |
| `pending_inputs` | Ожидающие вводы |
| `history_heads` | Заголовки истории |
| `history_turns` | Витки истории |
| `history_entries` | Записи истории |
| `session_archives` | Архивы сессий |
| `session_archives_v4` | Архивы v4 |
| `storage_meta` | Метаданные хранилища |

## Наши таблицы (добавленные)

### `memories` — плоская память (диалоги)

```sql
CREATE TABLE memories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_key TEXT NOT NULL,
    seq INTEGER NOT NULL,
    role TEXT NOT NULL,          -- user | assistant | tool
    content TEXT,
    timestamp TEXT,
    tool_calls TEXT,
    extra TEXT DEFAULT '{}'
);
```

Индексы: `idx_memories_session`, `idx_memories_seq`, `idx_memories_role`.

### `chunks` — чанки (фрагменты диалогов)

```sql
CREATE TABLE chunks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_key TEXT,
    message_id_from INTEGER,
    message_id_to INTEGER,
    chunk_no INTEGER DEFAULT 0,
    roles TEXT,
    ts_from TEXT,
    ts_to TEXT,
    kind TEXT,                   -- thought | user
    chunk_seq INTEGER,
    question TEXT,
    lang TEXT,                   -- ru | en | mixed
    content TEXT,
    content_raw TEXT,
    embedding BLOB,              -- [УДАЛЕНО] больше не используется
    model TEXT,
    rules_version TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now')),
    UNIQUE(session_key, message_id_from, message_id_to, chunk_no, rules_version)
);
```

> **Важно:** Колонка `embedding` удалена. Эмбеддинги хранятся только в `vec_chunks`.

Индексы: `idx_chunks_session`, `idx_chunks_message_id`.

### `memories_fts` — FTS5 (полнотекстовый поиск)

```sql
CREATE VIRTUAL TABLE memories_fts USING fts5(
    content,
    tokenize='unicode61',
    content=memories,
    content_rowid=id
);
```

Автоматически синхронизируется с `memories` через триггеры:
- `memories_ai` — AFTER INSERT
- `memories_ad` — AFTER DELETE
- `memories_au` — AFTER UPDATE

Служебные таблицы FTS5: `memories_fts_config`, `memories_fts_data`, `memories_fts_docsize`, `memories_fts_idx`.

### `vec_chunks` — sqlite-vec (векторный поиск)

```sql
CREATE VIRTUAL TABLE vec_chunks USING vec0(embedding float[1024]);
```

- Размерность: 1024 float (bge-m3)
- Формат BLOB: `struct.pack('1024f', *vec)` — 4096 байт
- Поиск: `SELECT v.distance, c.id, c.content, c.kind FROM vec_chunks v JOIN chunks c ON c.id = v.rowid WHERE v.embedding MATCH ? AND k = ?`

Служебные таблицы sqlite-vec: `vec_chunks_chunks`, `vec_chunks_info`, `vec_chunks_rowids`, `vec_chunks_vector_chunks00`.

### `worker_state` — состояние воркера эмбеддинга

```sql
CREATE TABLE worker_state (
    worker_id TEXT PRIMARY KEY,
    last_processed_id INTEGER NOT NULL DEFAULT 0,
    rules_version TEXT NOT NULL DEFAULT 'v2-snake',
    last_run_at TEXT,
    stats TEXT,
    last_error TEXT
);
```

### `embedding_errors` — ошибки эмбеддинга

```sql
CREATE TABLE embedding_errors (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id_from INTEGER NOT NULL,
    message_id_to INTEGER NOT NULL,
    content_hash TEXT NOT NULL,
    error_sample TEXT,
    error TEXT,
    attempts INTEGER NOT NULL DEFAULT 1,
    created_at TEXT DEFAULT (datetime('now')),
    UNIQUE(content_hash)
);
```

## Системная таблица

| Таблица | Назначение |
|---|---|
| `sqlite_sequence` | AUTOINCREMENT (стандартная SQLite) |

## Схема данных (связи)

```
memories ──→ chunks (message_id_from, message_id_to)
chunks   ──→ vec_chunks (chunks.id = vec_chunks.rowid)
memories ──→ memories_fts (memories.id = memories_fts.rowid)
```

## Процесс векторизации

1. Читаем `memories` после `worker_state.last_processed_id`
2. Фильтруем (пропускаем tool-записи, системные промпты, код)
3. Собираем чанки со скользящим окном (WINDOW_MIN=200, WINDOW_MAX=500 токенов, overlap=0.5)
4. Получаем эмбеддинги через bge-m3 (1024 dims)
5. Пишем в `chunks` (текстовые данные) и `vec_chunks` (BLOB)
6. Обновляем `worker_state.last_processed_id`
