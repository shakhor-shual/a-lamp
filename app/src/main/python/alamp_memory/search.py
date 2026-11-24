"""Поиск по SQLite-памяти: FTS5 + векторный (sqlite-vec)."""

from __future__ import annotations

import logging
import os
import sqlite3
import struct
import time
from dataclasses import dataclass, asdict
from typing import Any

from .fts_escape import fts5_escape

logger = logging.getLogger(__name__)


@dataclass
class SearchResult:
    """Одна найденная запись."""

    content: str
    kind: str = "?"
    source: str = "?"
    timestamp: str = ""
    distance: float | None = None
    source_id: int = 0
    session_key: str = ""

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def _dict_factory(cursor, row):
    """Row factory: sqlite3.Row → dict."""
    return dict(zip([col[0] for col in cursor.description], row))


def _open_db(db_path: str) -> sqlite3.Connection:
    """Открывает соединение с SQLite."""
    conn = sqlite3.connect(db_path)
    conn.row_factory = _dict_factory
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


def _vec_to_blob(vec: list[float]) -> bytes:
    """Конвертирует список float в BLOB для sqlite-vec."""
    return struct.pack(f"{len(vec)}f", *vec)


def _load_vec(conn: sqlite3.Connection) -> bool:
    """Пытается загрузить sqlite-vec расширение.

    Сначала пробует Python-пакет sqlite_vec, затем — прямую загрузку .so
    через load_extension (путь из SQLITE_VEC_SO_PATH).
    """
    try:
        import sqlite_vec  # noqa: F401

        conn.enable_load_extension(True)
        sqlite_vec.load(conn)  # type: ignore[attr-defined]
        conn.enable_load_extension(False)
        return True
    except Exception as exc:
        logger.debug("sqlite-vec Python-пакет не загружен: %s", exc)

    # Fallback: загружаем .so напрямую
    so_path = os.environ.get("SQLITE_VEC_SO_PATH", "")
    logger.info("_load_vec: so_path=%s exists=%s", so_path, os.path.exists(so_path) if so_path else "N/A")
    if so_path and os.path.exists(so_path):
        try:
            conn.enable_load_extension(True)
            conn.load_extension(so_path)
            conn.enable_load_extension(False)
            logger.info("sqlite-vec загружен через load_extension: %s", so_path)
            return True
        except Exception as exc:
            logger.warning("load_extension fallback не сработал: %s", exc)

    return False


# ─── FTS5 поиск ────────────────────────────────────────────────────────

def _search_fts(conn: sqlite3.Connection, query: str, limit: int = 5,
                role_filter: str | None = None) -> list[SearchResult]:
    """Полнотекстовый поиск через FTS5.

    Args:
        conn: Соединение с БД.
        query: Поисковый запрос.
        limit: Максимум результатов.
        role_filter: "user" | "assistant" | None (все роли).
    """
    safe_query = fts5_escape(query)
    if not safe_query:
        return []

    if role_filter:
        cur = conn.execute(
            """
            SELECT m.id, m.content, m.role, m.timestamp, m.session_key
            FROM memories_fts f
            JOIN memories m ON m.id = f.rowid
            WHERE memories_fts MATCH ? AND m.role = ?
            ORDER BY rank
            LIMIT ?
            """,
            (safe_query, role_filter, limit),
        )
    else:
        cur = conn.execute(
            """
            SELECT m.id, m.content, m.role, m.timestamp, m.session_key
            FROM memories_fts f
            JOIN memories m ON m.id = f.rowid
            WHERE memories_fts MATCH ?
            ORDER BY rank
            LIMIT ?
            """,
            (safe_query, limit),
        )
    rows = cur.fetchall()

    return [
        SearchResult(
            content=r["content"] or "",
            kind="memory",
            source=r["role"] or "?",
            timestamp=str(r["timestamp"] or ""),
            source_id=r["id"],
            session_key=r.get("session_key", ""),
        )
        for r in rows
    ]


# ─── Векторный поиск ───────────────────────────────────────────────────

def _search_vector(
    conn: sqlite3.Connection,
    query_embedding: list[float],
    limit: int = 5,
) -> tuple[list[SearchResult], str]:
    """Векторный поиск через sqlite-vec.

    Returns:
        Кортеж (результаты, метод): метод — "⚡ sqlite-vec".

    Raises:
        RuntimeError: Если sqlite-vec не загружен или vec_chunks не найдена.
    """
    if not _load_vec(conn):
        raise RuntimeError("sqlite-vec не загружен")

    cur = conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='vec_chunks'"
    )
    if not cur.fetchone():
        raise RuntimeError("vec_chunks не найдена")

    blob = _vec_to_blob(query_embedding)
    cur = conn.execute(
        """
        SELECT v.distance, c.id, c.content, c.kind, c.ts_from
        FROM vec_chunks v
        JOIN chunks c ON c.id = v.rowid
        WHERE v.embedding MATCH ?
          AND k = ?
        """,
        (blob, limit),
    )
    rows = cur.fetchall()

    return [
        SearchResult(
            content=r["content"] or "",
            kind=r["kind"] or "chunk",
            source="chunk",
            timestamp=str(r["ts_from"] or ""),
            distance=r["distance"],
            source_id=r["id"],
        )
        for r in rows
    ], "⚡ sqlite-vec"


# ─── Контекст вокруг записи ────────────────────────────────────────────

def _get_context_for_id(
    conn: sqlite3.Connection,
    source_id: int,
    before: int = 2,
    after: int = 2,
) -> list[SearchResult]:
    """Разворачивает контекст вокруг записи с source_id."""
    cur = conn.execute(
        "SELECT session_key, seq FROM memories WHERE id = ?",
        (source_id,),
    )
    row = cur.fetchone()
    if not row:
        return []

    session_key = row["session_key"]
    target_seq = row["seq"]

    cur = conn.execute(
        """
        SELECT id, content, role, timestamp, seq, session_key
        FROM memories
        WHERE session_key = ?
          AND seq BETWEEN ? AND ?
        ORDER BY seq
        """,
        (session_key, target_seq - before, target_seq + after),
    )
    rows = cur.fetchall()

    return [
        SearchResult(
            content=r["content"] or "",
            kind="memory",
            source=r["role"] or "?",
            timestamp=str(r["timestamp"] or ""),
            source_id=r["id"],
            session_key=r.get("session_key", ""),
        )
        for r in rows
    ]


# ─── Основная функция поиска ──────────────────────────────────────────

async def memory_recall(
    query: str,
    db_path: str,
    mode: str = "auto",
    limit: int = 5,
    expand: bool = True,
    context_before: int = 2,
    context_after: int = 2,
    api_key: str = "",
) -> tuple[list[SearchResult], str]:
    """Ищет в памяти.

    Args:
        query: Поисковый запрос.
        db_path: Путь к SQLite БД.
        mode: "auto" | "fts" | "vector" | "user" | "assistant".
        limit: Максимум результатов.
        expand: Если True — добавить контекст вокруг каждого результата.
        context_before: Сколько записей до (при expand=True).
        context_after: Сколько записей после (при expand=True).
        api_key: Ключ OpenRouter (для эмбеддинга).

    Returns:
        Кортеж (список SearchResult, строка с описанием использованного режима).
    """
    if not query.strip():
        return [], "пустой запрос"

    conn = _open_db(db_path)

    steps: list[str] = []
    mode_used = mode

    try:
        if mode == "vector":
            steps.append("🧠 эмбеддинг запроса...")
            from .embed import embed_text
            t0 = time.monotonic()
            vec = await embed_text(query, api_key=api_key, db_path=db_path)
            steps.append(f"✅ эмбеддинг получен ({len(vec)} dims)")
            results, vec_method = _search_vector(conn, vec, limit=limit)
            t1 = time.monotonic()
            steps.append(f"📊 {vec_method}: {len(results)} результатов за {t1-t0:.2f}s")
        elif mode == "user":
            results = _search_fts(conn, query, limit=limit, role_filter="user")
            steps.append(f"📄 FTS (user): {len(results)} результатов")
        elif mode == "assistant":
            results = _search_fts(conn, query, limit=limit, role_filter="assistant")
            steps.append(f"📄 FTS (assistant): {len(results)} результатов")
        elif mode == "fts":
            results = _search_fts(conn, query, limit=limit)
            steps.append(f"📄 FTS: {len(results)} результатов")
        else:  # auto
            steps.append("🧠 auto: пробую векторный поиск...")
            try:
                from .embed import embed_text
                steps.append("   эмбеддинг запроса...")
                t0 = time.monotonic()
                vec = await embed_text(query, api_key=api_key, db_path=db_path)
                steps.append(f"   ✅ эмбеддинг получен ({len(vec)} dims)")
                results, vec_method = _search_vector(conn, vec, limit=limit)
                t1 = time.monotonic()
                steps.append(f"   📊 {vec_method}: {len(results)} результатов за {t1-t0:.2f}s")
                if not results:
                    steps.append("   ⚠️ векторный поиск пуст → FTS fallback")
                    results = _search_fts(conn, query, limit=limit)
                    steps.append(f"   📄 FTS: {len(results)} результатов")
                else:
                    steps.append("   ✅ векторный поиск сработал")
            except Exception as exc:
                steps.append(f"   ❌ векторный упал: {exc}")
                steps.append("   → FTS fallback")
                results = _search_fts(conn, query, limit=limit)
                steps.append(f"   📄 FTS: {len(results)} результатов")
    finally:
        # Не закрываем conn здесь — он ещё нужен для expand ниже
        pass

    mode_used = " | ".join(steps)

    # Expand: добавляем контекст вокруг каждого результата
    if expand and results:
        expanded = []
        for r in results:
            ctx = _get_context_for_id(conn, r.source_id, context_before, context_after)
            if ctx:
                expanded.extend(ctx)
                expanded.append(SearchResult(
                    content="─── конец контекста ───",
                    kind="separator",
                    source="-",
                ))
            else:
                expanded.append(r)
        results = expanded

    conn.close()
    return results, mode_used


def memory_recall_sync(
    query: str,
    db_path: str,
    mode: str = "auto",
    limit: int = 5,
    expand: bool = True,
    context_before: int = 2,
    context_after: int = 2,
) -> list[SearchResult]:
    """Синхронный поиск (без эмбеддингов)."""
    if not query.strip():
        return []

    conn = _open_db(db_path)
    try:
        if mode == "user":
            results = _search_fts(conn, query, limit=limit, role_filter="user")
        elif mode == "assistant":
            results = _search_fts(conn, query, limit=limit, role_filter="assistant")
        else:
            results = _search_fts(conn, query, limit=limit)

        if expand and results:
            expanded = []
            for r in results:
                ctx = _get_context_for_id(conn, r.source_id, context_before, context_after)
                if ctx:
                    expanded.extend(ctx)
                    expanded.append(SearchResult(
                        content="─── конец контекста ───",
                        kind="separator",
                        source="-",
                    ))
                else:
                    expanded.append(r)
            results = expanded

        return results
    finally:
        conn.close()


def memory_recall_context(
    db_path: str,
    source_id: int,
    before: int = 5,
    after: int = 5,
) -> list[SearchResult]:
    """Разворачивает контекст вокруг записи с source_id.

    Args:
        db_path: Путь к SQLite БД.
        source_id: ID записи (memories.id).
        before: Сколько записей показать до.
        after: Сколько записей показать после.

    Returns:
        Список SearchResult.
    """
    conn = _open_db(db_path)
    try:
        return _get_context_for_id(conn, source_id, before, after)
    finally:
        conn.close()
