"""Проверка и установка переносимого архива агента."""
import hashlib
import json
import os
import shutil
import sqlite3
import stat
from contextlib import closing
from pathlib import Path, PurePosixPath
from uuid import uuid4
from zipfile import ZipFile

REQUIRED = {"sessions.sqlite3", "config.json", "alamp_config.json"}
WORKSPACE_DIRS = ("prompts", "skills", "memory")


def _allowed(name: str) -> bool:
    path = PurePosixPath(name)
    if path.as_posix() != name or "\\" in name or ".." in path.parts:
        return False
    if name in REQUIRED:
        return True
    if len(path.parts) < 2 or path.parts[0] != "workspace":
        return False
    if len(path.parts) == 2:
        return path.suffix == ".md"
    return path.parts[1] in WORKSPACE_DIRS and ".git" not in path.parts


def unpack_archive(archive: Path, stage: Path) -> None:
    with ZipFile(archive) as bundle:
        entries = bundle.infolist()
        names = {entry.filename for entry in entries}
        if len(names) != len(entries) or "manifest.json" not in names:
            raise ValueError("ZIP содержит дубликаты или отсутствует манифест")
        if bundle.getinfo("manifest.json").file_size > 8 * 1024 * 1024:
            raise ValueError("Манифест слишком большой")
        manifest = json.loads(bundle.read("manifest.json"))
        if manifest.get("format") != "alamp-agent" or manifest.get("version") != 1:
            raise ValueError("Версия архива не поддерживается")
        files = manifest["files"]
        if not isinstance(files, dict) or names != set(files) | {"manifest.json"} or not REQUIRED <= files.keys():
            raise ValueError("Состав ZIP не совпадает с манифестом")
        if sum(entry.file_size for entry in entries) > shutil.disk_usage(stage).free:
            raise OSError("Недостаточно места для распаковки")
        for name, expected in files.items():
            entry = bundle.getinfo(name)
            if not _allowed(name) or entry.is_dir() or stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError("Недопустимый путь в ZIP")
            if entry.file_size != expected["size"]:
                raise ValueError("Размер файла не совпадает с манифестом")
            destination = stage / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            digest = hashlib.sha256()
            with bundle.open(entry) as source, destination.open("xb") as target:
                for block in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(block)
                    target.write(block)
            if digest.hexdigest() != expected["sha256"]:
                raise ValueError("Контрольная сумма файла не совпадает с манифестом")


def validate_database(path: Path, max_version: int, vec_extension: str | None) -> None:
    with closing(sqlite3.connect(path, isolation_level=None)) as db:
        version, = db.execute("PRAGMA user_version").fetchone()
        if not 1 <= version <= max_version:
            raise ValueError("Версия базы не поддерживается")
        has_vectors = db.execute("SELECT 1 FROM sqlite_master WHERE name='vec_chunks'").fetchone()
        if has_vectors:
            if not vec_extension:
                raise ValueError("Для этой базы требуется sqlite-vec")
            db.enable_load_extension(True)
            try:
                db.load_extension(vec_extension)
            finally:
                db.enable_load_extension(False)
        if db.execute("PRAGMA quick_check").fetchall() != [("ok",)]:
            raise ValueError("SQLite обнаружила повреждение базы")
        db.execute("PRAGMA foreign_keys=ON")
        key = "websocket:" + str(uuid4())
        db.execute("BEGIN")
        try:
            db.execute("""INSERT INTO sessions
                (key, created_at, updated_at, metadata, last_archived, revision, generation, title, preview)
                VALUES (?, '', '', '{}', 0, 0, ?, '', '')""", (key, str(uuid4())))
            db.execute("""INSERT INTO messages (session_key, position, record) VALUES (?, 0, ?)
                ON CONFLICT(session_key, position) DO UPDATE SET record=excluded.record
                WHERE messages.record != excluded.record""",
                (key, '{"role":"user","content":"AI-Lamp import check","timestamp":"2026-01-01T00:00:00Z"}'))
        finally:
            db.rollback()
        db.execute("PRAGMA journal_mode=DELETE")


def retarget_session_workspaces(database: Path, workspace: Path, source_paths) -> int:
    """Переносит привязки диалогов к известному исходному workspace."""
    if not database.is_file():
        return 0
    destination = str(workspace)
    sources = {str(path).rstrip("/") for path in source_paths if path} - {destination}
    if not sources:
        return 0

    updated = 0
    with closing(sqlite3.connect(database)) as db, db:
        if not db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='sessions'").fetchone():
            return 0
        for old in sources:
            cursor = db.execute("""
                UPDATE sessions
                SET metadata = json_set(metadata, '$.workspace_scope.project_path', ?)
                WHERE json_valid(metadata)
                  AND COALESCE(
                      NULLIF(json_extract(metadata, '$.workspace_scope.project_path'), ''),
                      json_extract(metadata, '$.workspace_scope.path')
                  ) = ?
            """, (destination, old))
            updated += cursor.rowcount
    return updated


def replace_agent(stage: Path, workspace: Path, database: Path, config: Path, embeddings: Path) -> None:
    """Заменяет только переносимые данные и возвращает старые файлы при ошибке записи."""
    incoming = stage / "workspace"
    markdown = {file.name for file in workspace.glob("*.md") if file.is_file() or file.is_symlink()}
    markdown.update(file.name for file in incoming.glob("*.md"))
    moves = [(incoming / name, workspace / name) for name in (*sorted(markdown), *WORKSPACE_DIRS)]
    moves.extend([(stage / "config.json", config), (stage / "alamp_config.json", embeddings)])
    moves.extend((None, Path(str(database) + suffix)) for suffix in ("-wal", "-shm", "-journal"))
    moves.append((stage / "sessions.sqlite3", database))
    rollback = stage / "previous"
    rollback.mkdir()
    saved = []
    installed = []
    try:
        for index, (source, target) in enumerate(moves):
            if target.exists() or target.is_symlink():
                previous = rollback / str(index)
                os.replace(target, previous)
                saved.append((previous, target))
            if source is not None and source.exists():
                os.replace(source, target)
                installed.append((target, source))
    except OSError:
        for target, source in reversed(installed):
            os.replace(target, source)
        for previous, target in reversed(saved):
            os.replace(previous, target)
        raise
