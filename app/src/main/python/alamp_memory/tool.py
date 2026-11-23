"""Инструменты memory_recall / memory_recall_context для nanobot 0.4.1."""

from __future__ import annotations

import asyncio
import os
from pathlib import Path

from nanobot.agent.tools.base import Tool

from .search import memory_recall, memory_recall_sync, memory_recall_context, SearchResult


def _fmt_results(results: list[SearchResult], query: str, mode_info: str = "") -> str:
    """Форматирует результаты поиска в читаемый текст."""
    if not results:
        return f"По запросу «{query}» ничего не найдено."

    lines = [f"Найдено по запросу «{query}»:\n"]

    # Показываем режим поиска
    if mode_info:
        lines.append(f"🔍 Режим: {mode_info}\n")

    # Группируем по сессиям
    current_session = None
    for r in results:
        if r.kind == "separator":
            lines.append("")
            lines.append("───")
            lines.append("")
            continue

        # Заголовок сессии при смене
        if r.session_key and r.session_key != current_session:
            current_session = r.session_key
            # Извлекаем дату из первой записи сессии
            ses_date = r.timestamp[:10] if r.timestamp and len(r.timestamp) >= 10 else ""
            label = f"📁 Сессия: {current_session}"
            if ses_date:
                label += f"  |  {ses_date}"
            lines.append(label)
            lines.append("")

        ts = f" [{r.timestamp}]" if r.timestamp else ""
        role = f" ({r.source})" if r.source != "?" else ""
        marker = f"[ID={r.source_id}]{role}{ts}"
        lines.append(marker)
        text = (r.content or "")[:400]
        for line in text.split("\n")[:6]:
            lines.append(f"  {line}")
        lines.append("")

    # Инструкция по использованию второго инструмента
    lines.append(
        "─" * 40 + "\n"
        "💡 Чтобы увидеть полный диалог вокруг записи, вызови\n"
        "   memory_recall_context(source_id=<ID>, before=5, after=5)\n"
        "   Например: memory_recall_context(source_id=42, before=5, after=5)"
    )
    return "\n".join(lines)


def _fmt_context(results: list[SearchResult], source_id: int) -> str:
    """Форматирует контекст вокруг записи."""
    if not results:
        return f"Запись с ID {source_id} не найдена."

    lines = [f"Контекст вокруг записи #{source_id}:\n"]
    for r in results:
        marker = " ← НАЙДЕНА" if r.source_id == source_id else ""
        ts = f" [{r.timestamp}]" if r.timestamp else ""
        role = f" ({r.source})" if r.source != "?" else ""
        lines.append(f"[ID={r.source_id}]{role}{ts}{marker}")
        text = (r.content or "")[:600]
        for line in text.split("\n")[:10]:
            lines.append(f"  {line}")
        lines.append("")
    return "\n".join(lines)


class MemoryRecallTool(Tool):
    """Поиск по личной памяти nanobot (FTS5 + векторный)."""

    name = "memory_recall"
    description = (
        "Ищет информацию в личной памяти агента по прошлым разговорам. "
        "Используй для вопросов о событиях, фактах, договорённостях, "
        "которые обсуждались ранее. После поиска показывает ID записей "
        "в скобках [ID=...] — используй их в memory_recall_context."
    )
    parameters = {
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "minLength": 1,
                "description": "Что искать — фраза или ключевые слова",
            },
            "mode": {
                "type": "string",
                "enum": ["auto", "fts", "vector", "user", "assistant"],
                "default": "auto",
                "description": "Режим поиска: auto (векторный + FTS), fts (только текст), "
                               "vector (только векторный), user (только user), assistant",
            },
            "limit": {
                "type": "integer",
                "default": 5,
                "description": "Максимум результатов",
            },
            "expand": {
                "type": "boolean",
                "default": True,
                "description": "Показать контекст (1-2 записи до/после) вокруг каждого результата",
            },
        },
    }

    def __init__(self, db_path: str, api_key: str = ""):
        self._db_path = db_path
        self._api_key = api_key

    @classmethod
    def create(cls, ctx):
        db_path = os.environ.get("MEMORY_DB_PATH", "")
        if not db_path:
            workspace = getattr(ctx, 'workspace', None)
            if workspace:
                db_path = str(Path(workspace) / "sessions" / "sessions.sqlite3")
            else:
                db_path = "sessions.sqlite3"
        # Пробуем alamp_config.json, запасной — ENV
        api_key = ""
        try:
            from .config import load_services
            services = load_services(db_path)
            for svc in services:
                if svc.get("api_key"):
                    api_key = svc["api_key"]
                    break
        except Exception:
            api_key = os.environ.get("OPENROUTER_API_KEY", "")
        return cls(db_path, api_key)

    async def execute(
        self,
        query: str,
        mode: str = "auto",
        limit: int = 5,
        expand: bool = True,
    ) -> str:
        query = query.strip()
        if not query:
            return self.error("Укажите, что искать в памяти.")

        try:
            results, mode_used = await memory_recall(
                query=query,
                db_path=self._db_path,
                mode=mode,
                limit=limit,
                expand=expand,
                api_key=self._api_key,
            )
        except Exception as exc:
            results = await asyncio.to_thread(
                memory_recall_sync,
                query=query,
                db_path=self._db_path,
                mode=mode if mode in ("user", "assistant") else "fts",
                limit=limit,
                expand=expand,
            )
            mode_used = f"sync fallback ({mode} → fts)"

        return _fmt_results(results, query, mode_info=mode_used)


class MemoryRecallContextTool(Tool):
    """Разворачивает контекст вокруг найденной записи в памяти."""

    name = "memory_recall_context"
    description = (
        "Показывает полный диалог вокруг записи из памяти по её ID. "
        "ID бери из результатов memory_recall — они в скобках [ID=...]."
    )
    parameters = {
        "type": "object",
        "properties": {
            "source_id": {
                "type": "integer",
                "description": "ID записи (source_id) из результатов memory_recall — число в [ID=...]",
            },
            "before": {
                "type": "integer",
                "default": 5,
                "description": "Сколько записей показать до (по умолчанию 5)",
            },
            "after": {
                "type": "integer",
                "default": 5,
                "description": "Сколько записей показать после (по умолчанию 5)",
            },
        },
        "required": ["source_id"],
    }

    def __init__(self, db_path: str):
        self._db_path = db_path

    @classmethod
    def create(cls, ctx):
        db_path = os.environ.get("MEMORY_DB_PATH", "")
        if not db_path:
            workspace = getattr(ctx, 'workspace', None)
            if workspace:
                db_path = str(Path(workspace) / "sessions" / "sessions.sqlite3")
            else:
                db_path = "sessions.sqlite3"
        return cls(db_path)

    async def execute(self, source_id: int, before: int = 5, after: int = 5) -> str:
        try:
            results = await asyncio.to_thread(
                memory_recall_context,
                db_path=self._db_path,
                source_id=source_id,
                before=before,
                after=after,
            )
        except Exception as exc:
            return self.error(f"Ошибка получения контекста: {exc}")

        return _fmt_context(results, source_id)
