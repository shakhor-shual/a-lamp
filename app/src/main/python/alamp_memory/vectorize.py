"""Векторизация памяти: сборка чанков и запись эмбеддингов в sqlite-vec."""

from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import sqlite3
import struct
import time
from collections import OrderedDict
from typing import Any

logger = logging.getLogger(__name__)

# ─── Конфиг ─────────────────────────────────────────────────────────────
WINDOW_MIN = 200       # мин. токенов в чанке
WINDOW_MAX = 500       # макс. токенов в чанке
OVERLAP = 0.5          # доля перекрытия окон
BATCH_SIZE = 8         # текстов на запрос эмбеддинга
MAX_ATTEMPTS = 3       # попыток на чанк
READ_CHUNK = 500       # записей memories за проход
WORKER_ID = "raw-v2"
RULES_VERSION = "v2-snake"
MODEL = "bge-m3-q4_k_m"
EMBED_DIM = 1024

# ─── Очистка текста ────────────────────────────────────────────────────
_MD = re.compile(r"[*_`#]{1,3}|!?\[[^\]]*\]\([^)]*\)|<[^>]+>")
_WS = re.compile(r"[ \t]{2,}")
_EOL = re.compile(r"\n{3,}")
_STT = re.compile(
    r"^(user talked via voice recognition[^\n]*|\[\d{2}:\d{2}(:\d{2})?[^\]]*\])\s*",
    re.I,
)
_CODE_LIKE = re.compile(
    r"^(def |class |import |from |#!/|# |```|SELECT |CREATE |ALTER |INSERT |UPDATE |DELETE |TRUNCATE )",
    re.M,
)


def clean_text(raw: str) -> str:
    """Очищает текст от markdown-разметки и лишних пробелов."""
    if not raw:
        return ""
    t = raw.replace("\r\n", "\n").replace("\r", "\n")
    t = _MD.sub("", t)
    t = _STT.sub("", t)
    t = _WS.sub(" ", t)
    t = _EOL.sub("\n\n", t)
    t = t.encode("utf-8", errors="replace").decode("utf-8")
    return t.strip()


def detect_lang(text: str) -> str:
    """Определяет язык текста: ru / en / mixed."""
    if not text:
        return "ru"
    letters = [c for c in text if c.isalpha()]
    if not letters:
        return "ru"
    cyr = sum(1 for c in letters if "\u0400" <= c <= "\u04FF")
    lat = len(letters) - cyr
    if cyr and lat:
        return "mixed" if min(cyr, lat) / len(letters) > 0.15 else ("ru" if cyr > lat else "en")
    return "ru" if cyr else "en"


def count_tokens(text: str) -> int:
    """Приблизительный подсчёт токенов."""
    if not text:
        return 0
    letters = [c for c in text if c.isalpha()]
    if not letters:
        return max(1, len(text) // 4)
    cyr = sum(1 for c in letters if "\u0400" <= c <= "\u04FF")
    div = 5 if cyr / len(letters) > 0.5 else 4
    return max(1, len(text) // div)


def looks_like_code(text: str) -> bool:
    return bool(_CODE_LIKE.search(text))


def looks_like_system_prompt(text: str) -> bool:
    return bool(
        re.search(
            r"(AGENTS\.md|SOUL\.md|USER\.md|SYSTEM_PROMPT|role\s*=\s*['\"]system['\"])",
            text,
            re.I,
        )
    )


# ─── Фильтр сообщений ──────────────────────────────────────────────────
_TOOL_ALLOWLIST = {"web_search", "web_fetch", "read_file", "exec"}


def _tool_ok(extra: dict) -> bool:
    name = extra.get("name", "")
    return name in _TOOL_ALLOWLIST


def passes_filter(role: str, content: str, extra: dict, is_short_user: bool, next_is_assistant: bool) -> bool:
    """Проверяет, нужно ли включать сообщение в чанки."""
    if role in ("user", "assistant"):
        return True
    if role == "tool":
        if not _tool_ok(extra):
            return False
        name = extra.get("name")
        if name == "read_file" and (looks_like_code(content) or looks_like_system_prompt(content)):
            return False
        return True
    return False


def get_selector(msg: dict) -> str:
    """Возвращает префикс для роли сообщения."""
    role = msg.get("role")
    extra = msg.get("extra") or {}
    if role == "user":
        return "мне сказали: "
    if role == "assistant":
        return "я подумала и ответила: "
    if role == "tool":
        name = extra.get("name")
        if name == "read_file":
            return "я прочла: "
        if name in ("web_search", "web_fetch"):
            return "я нашла в интернете: "
    return ""


# ─── Загрузка сообщений ────────────────────────────────────────────────
def load_messages(conn: sqlite3.Connection, after_id: int, limit: int | None = None,
                  max_id: int | None = None) -> list[dict]:
    """Загружает сообщения из memories после after_id."""
    if max_id is not None:
        cur = conn.execute(
            "SELECT id, session_key, role, content, timestamp, extra "
            "FROM memories "
            "WHERE id > ? AND id <= ? AND content IS NOT NULL "
            "ORDER BY id LIMIT ?",
            (after_id, max_id, limit or READ_CHUNK),
        )
    else:
        cur = conn.execute(
            "SELECT id, session_key, role, content, timestamp, extra "
            "FROM memories "
            "WHERE id > ? AND content IS NOT NULL "
            "ORDER BY id LIMIT ?",
            (after_id, limit or READ_CHUNK),
        )
    rows = []
    for row in cur.fetchall():
        extra = {}
        try:
            extra = json.loads(row[5]) if row[5] else {}
        except (json.JSONDecodeError, TypeError):
            extra = {}
        rows.append({
            "id": row[0],
            "session_key": row[1],
            "role": row[2],
            "content": row[3],
            "timestamp": row[4],
            "extra": extra,
        })
    return rows


# ─── Сборка чанков ─────────────────────────────────────────────────────
def make_chunk(parts: list) -> dict | None:
    """Собирает один чанк из списка (msg, text)."""
    enriched = []
    for msg, t in parts:
        if not t:
            continue
        selector = get_selector(msg)
        enriched.append(f"{selector}{t}")
    content = "\n\n".join(enriched)
    if not content:
        return None
    msgs = [m for m, _ in parts]
    from_id = min(m["id"] for m in msgs)
    to_id = max(m["id"] for m in msgs)
    roles = list(OrderedDict.fromkeys(m["role"] for m in msgs))
    question = None
    for m, t in parts:
        if m["role"] == "user" and t:
            question = t[:500]
            break
    return {
        "session_key": msgs[0]["session_key"],
        "message_id_from": from_id,
        "message_id_to": to_id,
        "chunk_no": 0,
        "roles": json.dumps(roles, ensure_ascii=False),
        "ts_from": min(m["timestamp"] for m in msgs),
        "ts_to": max(m["timestamp"] for m in msgs),
        "kind": "thought",
        "question": question,
        "lang": detect_lang(content),
        "content": content,
        "content_raw": None,
        "model": MODEL,
        "rules_version": RULES_VERSION,
    }


def build_chunks(stream: list[dict]) -> list[dict]:
    """Собирает чанки из потока сообщений (скользящее окно)."""
    segs = []
    pos = 0
    for msg in stream:
        t = clean_text(msg.get("content") or "")
        if msg.get("role") == "assistant":
            extra = msg.get("extra") or {}
            r = extra.get("reasoning_content") or extra.get("thinking")
            if r:
                rt = clean_text(r)
                if rt:
                    t = (t + "\n\n[мои размышления]\n" + rt) if t else "[мои размышления]\n" + rt
        if not t:
            continue
        n = count_tokens(t)
        segs.append((msg, t, pos, pos + n))
        pos += n

    if not segs:
        return []
    total = pos

    if total < WINDOW_MIN:
        return [make_chunk([(m, t) for m, t, _, _ in segs])]

    chunks = []
    start = 0
    while start < total:
        end = min(start + WINDOW_MAX, total)
        parts = []
        for msg, t, s, e in segs:
            if e <= start or s >= end:
                continue
            t_start = max(0, int((start - s) * len(t) // max(1, e - s))) if s < start else 0
            t_end = min(len(t), int((end - s) * len(t) // max(1, e - s))) if e > end else len(t)
            sub = t[t_start:t_end].strip()
            if sub:
                parts.append((msg, sub))
        ch = make_chunk(parts)
        if ch:
            chunks.append(ch)
        if end >= total:
            break
        start = end - int(WINDOW_MAX * OVERLAP)

    # Нумеруем чанки внутри одной группы
    by_key: dict[tuple, list] = {}
    for ch in chunks:
        key = (ch["session_key"], ch["message_id_from"], ch["message_id_to"])
        by_key.setdefault(key, []).append(ch)
    for lst in by_key.values():
        for i, ch in enumerate(lst):
            ch["chunk_no"] = i
    return chunks


# ─── Эмбеддинг ─────────────────────────────────────────────────────────
async def _embed_batch(texts: list[str], db_path: str, api_key: str = "") -> list[list[float]] | None:
    """Получает эмбеддинги для батча текстов через embed_text (по одному)."""
    from .embed import embed_text

    results = []
    for text in texts:
        try:
            vec = await embed_text(text, api_key=api_key, db_path=db_path, for_indexing=True)
            results.append(vec)
        except Exception as exc:
            logger.warning("embed_text error: %s", exc)
            return None
    return results


def _vec_to_blob(vec: list[float]) -> bytes:
    """Конвертирует список float в BLOB для sqlite-vec."""
    return struct.pack(f"{len(vec)}f", *vec)


# ─── Работа с worker_state ─────────────────────────────────────────────
def get_state(conn: sqlite3.Connection) -> tuple[int, str]:
    """Читает состояние воркера."""
    cur = conn.execute(
        "SELECT last_processed_id, rules_version FROM worker_state WHERE worker_id=?",
        (WORKER_ID,),
    )
    row = cur.fetchone()
    if row:
        return row[0], row[1]
    conn.execute(
        "INSERT OR IGNORE INTO worker_state (worker_id, rules_version) VALUES (?, ?)",
        (WORKER_ID, RULES_VERSION),
    )
    conn.commit()
    return 0, RULES_VERSION


def set_state(conn: sqlite3.Connection, last_id: int, stats: dict) -> None:
    """Обновляет состояние воркера."""
    conn.execute(
        """UPDATE worker_state
           SET last_processed_id=?, last_run_at=datetime('now'), stats=?, rules_version=?, last_error=NULL
           WHERE worker_id=?""",
        (last_id, json.dumps(stats, ensure_ascii=False), RULES_VERSION, WORKER_ID),
    )
    conn.commit()


# ─── Запись в БД ───────────────────────────────────────────────────────
def _write_chunk(conn: sqlite3.Connection, ch: dict) -> int:
    """Пишет чанк в таблицу chunks, возвращает rowid."""
    cur = conn.execute(
        """INSERT INTO chunks
           (session_key, message_id_from, message_id_to, chunk_no, roles,
            ts_from, ts_to, kind, chunk_seq, question, lang,
            content, content_raw, model, rules_version)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (
            ch["session_key"], ch["message_id_from"], ch["message_id_to"],
            ch["chunk_no"], ch["roles"],
            ch["ts_from"], ch["ts_to"], ch["kind"], None, ch["question"],
            ch["lang"], ch["content"], ch["content_raw"],
            ch["model"], ch["rules_version"],
        ),
    )
    return cur.lastrowid


def _write_vec(conn: sqlite3.Connection, rowid: int, embedding: list[float]) -> None:
    """Пишет эмбеддинг в vec_chunks."""
    blob = _vec_to_blob(embedding)
    conn.execute(
        "INSERT OR IGNORE INTO vec_chunks(rowid, embedding) VALUES (?, ?)",
        (rowid, blob),
    )


def _load_vec(conn: sqlite3.Connection) -> bool:
    """Загружает sqlite-vec расширение."""
    try:
        import sqlite_vec  # noqa: F401
        conn.enable_load_extension(True)
        sqlite_vec.load(conn)  # type: ignore[attr-defined]
        conn.enable_load_extension(False)
        return True
    except Exception as exc:
        logger.warning("sqlite-vec не загружен: %s", exc)
        return False


# ─── Один проход ───────────────────────────────────────────────────────
async def vectorize_once(
    conn: sqlite3.Connection,
    after_id: int,
    db_path: str = "",
    api_key: str = "",
    limit: int | None = None,
    max_id: int | None = None,
) -> tuple[int, dict]:
    """Один проход векторизации: читает memories → собирает чанки → пишет эмбеддинги.

    Returns:
        (new_last_id, stats)
    """
    stats = {"read": 0, "filtered": 0, "chunks": 0, "embedded": 0, "errors": 0}

    if max_id is not None and after_id >= max_id:
        return after_id, stats

    rows = load_messages(conn, after_id, limit, max_id)
    if not rows:
        return after_id, stats
    stats["read"] = len(rows)

    # Группируем по сессиям
    by_session = OrderedDict()
    for m in rows:
        by_session.setdefault(m["session_key"], []).append(m)

    # Фильтруем
    kept = []
    for sk, msgs in by_session.items():
        sorted_msgs = sorted(msgs, key=lambda m: m["id"])
        for i, m in enumerate(sorted_msgs):
            nxt = sorted_msgs[i + 1] if i + 1 < len(sorted_msgs) else None
            is_short = len(m.get("content") or "") < 30
            if passes_filter(
                m["role"], m.get("content") or "", m.get("extra") or {},
                is_short, nxt is not None and nxt["role"] == "assistant",
            ):
                kept.append(m)
    stats["filtered"] = stats["read"] - len(kept)
    if not kept:
        new_after = max(m["id"] for m in rows)
        set_state(conn, new_after, stats)
        return new_after, stats

    # Собираем чанки
    kept_by_session = OrderedDict()
    for m in kept:
        kept_by_session.setdefault(m["session_key"], []).append(m)

    all_chunks = []
    for sk, msgs in kept_by_session.items():
        all_chunks.extend(build_chunks(msgs))
    all_chunks = [c for c in all_chunks if c]
    stats["chunks"] = len(all_chunks)
    if not all_chunks:
        new_after = max(m["id"] for m in rows)
        set_state(conn, new_after, stats)
        return new_after, stats

    # Загружаем sqlite-vec
    if not _load_vec(conn):
        logger.error("sqlite-vec не загружен, пропускаем эмбеддинги")
        new_after = max(m["id"] for m in rows)
        set_state(conn, new_after, stats)
        return new_after, stats

    # Обрабатываем батчами
    failed = []
    for i in range(0, len(all_chunks), BATCH_SIZE):
        batch = all_chunks[i:i + BATCH_SIZE]
        texts = [c["content"] for c in batch]
        vecs = await _embed_batch(texts, db_path, api_key)
        if vecs is None:
            failed.extend(batch)
            continue

        for ch, vec in zip(batch, vecs):
            rowid = _write_chunk(conn, ch)
            _write_vec(conn, rowid, vec)
        conn.commit()
        stats["embedded"] += len(batch)
        logger.info("  +%d чанков (id до %d)", len(batch), batch[-1]["message_id_to"])

    for ch in failed:
        logger.warning("  ❌ ошибка эмбеддинга для чанка %d-%d",
                       ch["message_id_from"], ch["message_id_to"])
        stats["errors"] += 1

    new_after = max(m["id"] for m in rows)
    set_state(conn, new_after, stats)
    return new_after, stats


# ─── Полная перестройка ────────────────────────────────────────────────
async def vectorize_rebuild(
    conn: sqlite3.Connection,
    db_path: str = "",
    api_key: str = "",
) -> dict:
    """Полная перестройка: очистка и перевекторизация с нуля."""
    logger.info("REBUILD: очистка chunks/vec_chunks/embedding_errors")

    # Очищаем
    conn.execute("DELETE FROM chunks")
    conn.execute("DELETE FROM embedding_errors")
    if _load_vec(conn):
        conn.execute("DELETE FROM vec_chunks")
    conn.execute("UPDATE worker_state SET last_processed_id=0 WHERE worker_id=?", (WORKER_ID,))
    conn.commit()

    pos, _ = get_state(conn)
    total = {"read": 0, "filtered": 0, "chunks": 0, "embedded": 0, "errors": 0}
    t0 = time.time()

    while True:
        pos, stats = await vectorize_once(conn, pos, db_path, api_key)
        for k in total:
            total[k] += stats[k]
        if stats["read"] == 0:
            break
        logger.info("  проход: %s", stats)

    logger.info("REBUILD завершён за %.0fs: %s", time.time() - t0, total)
    return total


# ─── Статус векторизации ───────────────────────────────────────────────
def vectorize_status(db_path: str) -> str:
    """Показывает статистику векторизации."""
    if not os.path.exists(db_path):
        return f"❌ База не найдена: {db_path}"

    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row

    try:
        # worker_state
        cur = conn.execute("SELECT * FROM worker_state WHERE worker_id=?", (WORKER_ID,))
        ws = cur.fetchone()

        # memories
        cur = conn.execute("SELECT MAX(id) FROM memories")
        max_mem = cur.fetchone()[0] or 0
        cur = conn.execute("SELECT MIN(id) FROM memories")
        min_mem = cur.fetchone()[0] or 0
        cur = conn.execute("SELECT COUNT(*) FROM memories")
        total_mem = cur.fetchone()[0]

        # chunks
        cur = conn.execute("SELECT COUNT(*) FROM chunks")
        total_chunks = cur.fetchone()[0]
        cur = conn.execute("SELECT MIN(message_id_from), MAX(message_id_to) FROM chunks")
        r = cur.fetchone()
        chunks_from = r[0] or 0
        chunks_to = r[1] or 0

        # vec_chunks
        _load_vec(conn)
        try:
            cur = conn.execute("SELECT COUNT(*) FROM vec_chunks")
            total_vec = cur.fetchone()[0]
        except Exception:
            total_vec = -1

        # Необработанные
        last_id = ws["last_processed_id"] if ws else 0
        cur = conn.execute("SELECT COUNT(*) FROM memories WHERE id > ?", (last_id,))
        pending = cur.fetchone()[0]

        lines = [
            "📊 **Статус векторизации:**\n",
            f"  **worker_state:** last_processed_id = {last_id}",
            f"  **memories:** всего {total_mem}, id {min_mem} .. {max_mem}",
            f"  **chunks:** всего {total_chunks}, покрывают id {chunks_from} .. {chunks_to}",
            f"  **vec_chunks:** {total_vec if total_vec >= 0 else 'недоступно (sqlite-vec)'}",
            f"  **необработано:** {pending} записей после last_processed_id",
        ]

        if pending == 0 and last_id >= max_mem:
            lines.append("\n  ✅ **Всё векторизовано.** Новых записей нет.")
        elif pending == 0 and last_id < max_mem:
            lines.append(f"\n  ⚠️ **Несоответствие:** last_processed_id ({last_id}) < MAX(id) ({max_mem}), "
                         f"но pending = 0. Возможно, записи отфильтрованы.")
        else:
            lines.append(f"\n  🔄 **Требуется инкрементальная векторизация:** {pending} записей.")

        return "\n".join(lines)
    finally:
        conn.close()


# ─── Основная функция для вызова из инструмента ────────────────────────
async def run_vectorize(
    db_path: str,
    mode: str = "incremental",
    api_key: str = "",
) -> str:
    """Запускает векторизацию SQLite базы.

    Args:
        db_path: Путь к SQLite БД.
        mode: "incremental" — добрать новые, "rebuild" — перестроить всё,
              "status" — показать статистику.
        api_key: Ключ OpenRouter для эмбеддинга (если нет в конфиге).

    Returns:
        Строка с результатом.
    """
    if not os.path.exists(db_path):
        return f"❌ База не найдена: {db_path}"

    if mode == "status":
        return vectorize_status(db_path)

    conn = sqlite3.connect(db_path)
    conn.execute("PRAGMA journal_mode=WAL")

    try:
        if mode == "rebuild":
            total = await vectorize_rebuild(conn, db_path, api_key)
            return (
                f"✅ Перестройка завершена:\n"
                f"  прочитано: {total['read']}\n"
                f"  отфильтровано: {total['filtered']}\n"
                f"  собрано чанков: {total['chunks']}\n"
                f"  векторизовано: {total['embedded']}\n"
                f"  ошибок: {total['errors']}"
            )
        else:  # incremental
            pos, _ = get_state(conn)
            cur = conn.execute("SELECT MAX(id) FROM memories")
            max_id = cur.fetchone()[0]
            if max_id is None or pos >= max_id:
                conn.close()
                return "✅ Всё уже векторизовано (новых записей нет)"

            pos, stats = await vectorize_once(conn, pos, db_path, api_key, max_id=max_id)
            return (
                f"✅ Инкрементальная векторизация:\n"
                f"  прочитано: {stats['read']}\n"
                f"  отфильтровано: {stats['filtered']}\n"
                f"  собрано чанков: {stats['chunks']}\n"
                f"  векторизовано: {stats['embedded']}\n"
                f"  ошибок: {stats['errors']}"
            )
    finally:
        conn.close()
