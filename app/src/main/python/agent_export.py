"""Снимок данных агента и переносимый ZIP-архив."""

import hashlib
import json
import shutil
import sqlite3
from contextlib import closing
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


def snapshot_agent(
    snapshot: Path, workspace: Path, database: Path, config: Path, embedding_config: Path
) -> None:
    # SQLite backup читает согласованное состояние, включая зафиксированные страницы WAL.
    with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as source:
        with closing(sqlite3.connect(snapshot / "sessions.sqlite3")) as target:
            source.backup(target)
            target.execute("PRAGMA journal_mode=DELETE")

    shutil.copyfile(config, snapshot / "config.json")
    shutil.copyfile(embedding_config, snapshot / "alamp_config.json")

    paths = list(workspace.glob("*.md"))
    for folder in ("prompts", "skills"):
        root = workspace / folder
        if root.is_dir() and not root.is_symlink():
            paths.extend(root.rglob("*"))
    memory = workspace / "memory"
    if not memory.is_symlink():
        paths.extend(memory / name for name in (
            "MEMORY.md", "history.jsonl", "HISTORY.md", ".cursor", ".dream_cursor",
        ))

    for source in paths:
        relative = source.relative_to(workspace)
        if ".git" in relative.parts or not source.is_file() or source.is_symlink() or workspace not in source.resolve().parents:
            continue
        destination = snapshot / "workspace" / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)


def write_archive(snapshot: Path, archive: Path) -> None:
    manifest = {"format": "alamp-agent", "version": 1, "files": {}}
    with ZipFile(archive, "w", compression=ZIP_DEFLATED, compresslevel=3) as bundle:
        for file in sorted(snapshot.rglob("*")):
            if not file.is_file():
                continue
            name = file.relative_to(snapshot).as_posix()
            digest = hashlib.sha256()
            size = 0
            with file.open("rb") as source, bundle.open(name, "w", force_zip64=True) as target:
                for block in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(block)
                    target.write(block)
                    size += len(block)
            manifest["files"][name] = {"sha256": digest.hexdigest(), "size": size}
        bundle.writestr("manifest.json", json.dumps(manifest, indent=2, ensure_ascii=False))
