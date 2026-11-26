"""Чтение и атомарное сохранение файлов редактора AI-Lamp."""

import hashlib
import json
import math
import os
from pathlib import Path
from tempfile import NamedTemporaryFile
from urllib.parse import urlsplit

CONFIG_FILES = ("config.json", "alamp_config.json")


def list_files(config_dir: Path, workspace: Path | None, section: str) -> list[str]:
    if section == "configs":
        return [name for name in CONFIG_FILES if (config_dir / name).is_file()]
    if section != "prompts" or workspace is None:
        raise ValueError("Рабочая папка Nanobot ещё не доступна.")
    paths = list(workspace.glob("*.md"))
    prompts = workspace / "prompts"
    if not prompts.is_symlink():
        paths.extend(prompts.rglob("*.md"))
    paths.append(workspace / "memory" / "MEMORY.md")
    return sorted(path.relative_to(workspace).as_posix() for path in paths
                  if path.is_file() and not path.is_symlink() and workspace in path.resolve().parents)


def _file(config_dir: Path, workspace: Path | None, section: str, name: str) -> Path:
    if name not in list_files(config_dir, workspace, section):
        raise ValueError("Файл больше не доступен. Открой список заново.")
    root = config_dir if section == "configs" else workspace
    path = root / name
    if path.is_symlink():
        raise ValueError("Редактор не открывает символические ссылки.")
    return path


def _object(text: str) -> dict:
    def reject_duplicates(pairs):
        data = {}
        for key, value in pairs:
            if key in data:
                raise ValueError("JSON содержит повторяющийся ключ.")
            data[key] = value
        return data

    def reject_constant(_):
        raise ValueError("JSON не допускает NaN и Infinity.")

    try:
        data = json.loads(text, object_pairs_hook=reject_duplicates, parse_constant=reject_constant)
    except json.JSONDecodeError as error:
        raise ValueError(f"Ошибка JSON, строка {error.lineno}, столбец {error.colno}.") from None
    if not isinstance(data, dict):
        raise ValueError("В корне конфигурации должен быть JSON-объект.")
    return data


def _format_json(data: dict) -> str:
    try:
        return json.dumps(data, indent=2, ensure_ascii=False, allow_nan=False) + "\n"
    except ValueError:
        raise ValueError("JSON содержит число вне допустимого диапазона.") from None


def _validate_config(name: str, text: str) -> str:
    data = _object(text)
    formatted = _format_json(data)
    if name == "config.json":
        from nanobot.config.loader import _migrate_config
        from nanobot.config.schema import Config
        from pydantic import ValidationError

        try:
            Config.model_validate(_migrate_config(data))
        except ValidationError as error:
            issues = []
            for item in error.errors(include_url=False, include_input=False)[:5]:
                field = ".".join(str(part) for part in item["loc"]) or "config.json"
                issues.append(f"{field}: {item['msg']}")
            raise ValueError("\n".join(issues)) from None
    else:
        services = data.get("embedding_services", [])
        if not isinstance(services, list):
            raise ValueError("embedding_services должен быть массивом.")
        for index, service in enumerate(services, 1):
            prefix = f"Сервис эмбеддингов {index}"
            if not isinstance(service, dict):
                raise ValueError(f"{prefix}: ожидается JSON-объект.")
            url = service.get("url")
            try:
                parsed = urlsplit(url) if isinstance(url, str) else None
            except ValueError:
                parsed = None
            if not parsed or parsed.scheme not in ("http", "https") or not parsed.hostname:
                raise ValueError(f"{prefix}: укажи полный URL с http:// или https://.")
            for field in ("api_key", "model"):
                if field in service and not isinstance(service[field], str):
                    raise ValueError(f"{prefix}: {field} должен быть строкой.")
            if "indexing" in service and not isinstance(service["indexing"], bool):
                raise ValueError(f"{prefix}: indexing должен быть true или false.")
            timeout = service.get("timeout")
            if timeout is not None and (
                type(timeout) not in (int, float)
                or timeout <= 0
                or (isinstance(timeout, float) and not math.isfinite(timeout))
            ):
                raise ValueError(f"{prefix}: timeout должен быть положительным числом.")
    return formatted


def read_file(config_dir: Path, workspace: Path | None, section: str, name: str) -> dict:
    path = _file(config_dir, workspace, section, name)
    raw = path.read_bytes()
    text = raw.decode("utf-8")
    if section == "configs":
        try:
            text = _format_json(_object(text))
        except ValueError:
            pass  # Повреждённый JSON тоже можно открыть и исправить.
    backup = path.with_name(path.name + ".bak")
    return {
        "text": text,
        "revision": hashlib.sha256(raw).hexdigest(),
        "backup": section == "configs" and backup.is_file() and not backup.is_symlink(),
    }


def _atomic_write(path: Path, content: bytes) -> None:
    temporary = None
    try:
        with NamedTemporaryFile(dir=path.parent, prefix="." + path.name + ".", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def write_file(
    config_dir: Path, workspace: Path | None, section: str, name: str,
    revision: str, text: str | None,
) -> dict:
    path = _file(config_dir, workspace, section, name)
    previous = path.read_bytes()
    if hashlib.sha256(previous).hexdigest() != revision:
        raise ValueError("Файл изменился. Скопируй свой текст и открой файл заново.")
    backup = path.with_name(path.name + ".bak")
    if text is None:
        if section != "configs" or not backup.is_file() or backup.is_symlink():
            raise ValueError("Предыдущая версия конфигурации недоступна.")
        restored = backup.read_bytes()
        _validate_config(name, restored.decode("utf-8"))
        _atomic_write(path, restored)
    else:
        updated = (_validate_config(name, text) if section == "configs" else text).encode("utf-8")
        if updated != previous:
            if section == "configs":
                _atomic_write(backup, previous)
            _atomic_write(path, updated)
    return read_file(config_dir, workspace, section, name)
