"""Запуск шлюза nanobot 0.4.1 на Android."""

import asyncio
import sys
import os
import json
import threading
import traceback

# --- Monkey-patch: подменяем sqlite3 на pysqlite3 (с поддержкой load_extension) ---
# Должен быть ДО любого импорта alamp_memory, который импортирует sqlite3
try:
    import pysqlite3
    sys.modules["sqlite3"] = pysqlite3
    # Проверяем, что enable_load_extension доступен
    _test_conn = pysqlite3.connect(":memory:")
    _test_conn.enable_load_extension(True)
    _test_conn.close()
    del _test_conn
except Exception:
    pass  # Если pysqlite3 нет — остаётся стандартный sqlite3
# --- Конец monkey-patch pysqlite3 ---

# --- Monkey-patch: регистрируем инструменты alamp_memory ---
try:
    from nanobot.agent.tools.registry import ToolRegistry
    from alamp_memory.tool import MemoryRecallTool, MemoryRecallContextTool

    _orig_tool_registry_init = ToolRegistry.__init__

    def _patched_tool_registry_init(self, *args, **kwargs):
        _orig_tool_registry_init(self, *args, **kwargs)
        for tool_cls in (MemoryRecallTool, MemoryRecallContextTool):
            try:
                tool = tool_cls.create(self)
                self.register(tool)
            except Exception as e:
                import traceback as _tb
                log_path = os.environ.get("MEMORY_DB_PATH", "/tmp")
                log_dir = os.path.dirname(log_path)
                err_path = os.path.join(log_dir, f"{tool_cls.__name__}_register_error.log")
                os.makedirs(log_dir, exist_ok=True)
                with open(err_path, "w") as f:
                    f.write(f"{tool_cls.__name__} register failed: {e}\n{_tb.format_exc()}")

    ToolRegistry.__init__ = _patched_tool_registry_init
except ImportError:
    pass  # Если нет nanobot или alamp_memory — не страшно
# --- Конец monkey-patch ---

_gateway_lock = threading.Lock()
_gateway_thread = None
_gateway_stop = None
_gateway_paths = None
_WEB_PORT = 8766
_HEALTH_PORT = 18791


def start_gateway(data_dir: str) -> str:
    """
    Запускает шлюз nanobot в фоновом потоке.
    """
    try:
        with _gateway_lock:
            if _gateway_thread is None or not _gateway_thread.is_alive():
                _run_gateway(data_dir)
        return "ok"
    except Exception as e:
        tb = traceback.format_exc()
        log_path = os.path.join(data_dir, "nanobot", "gateway_error.log")
        os.makedirs(os.path.dirname(log_path), exist_ok=True)
        with open(log_path, "w") as f:
            f.write(f"Error: {e}\n{tb}")
        return f"ERROR: {e}"


def export_agent(data_dir: str, archive_path: str, progress=None) -> None:
    """Останавливает шлюз на время подготовки снимка агента."""
    from tempfile import TemporaryDirectory
    from pathlib import Path

    from agent_export import snapshot_agent, write_archive

    archive = Path(archive_path)
    with TemporaryDirectory(prefix="agent-export-", dir=archive.parent) as directory:
        snapshot = Path(directory)
        with _gateway_lock:
            paths = _gateway_paths
            if paths is None or _gateway_thread is None or not _gateway_thread.is_alive():
                raise RuntimeError("Шлюз ещё не готов")

            if progress is not None:
                progress.accept(1)
            _gateway_stop.set()
            _gateway_thread.join()
            try:
                if progress is not None:
                    progress.accept(2)
                snapshot_agent(snapshot, *paths)
            finally:
                _run_gateway(data_dir, check_bootstrap=False)

        if progress is not None:
            progress.accept(3)
        write_archive(snapshot, archive)


def import_agent(data_dir: str, archive_path: str, progress=None) -> None:
    from pathlib import Path
    from tempfile import TemporaryDirectory

    from agent_import import replace_agent, retarget_session_workspaces, unpack_archive, validate_database
    from nanobot.config.loader import _migrate_config
    from nanobot.config.schema import Config
    from nanobot.session.sqlite_store import _SCHEMA_MIGRATIONS

    with TemporaryDirectory(prefix=".agent-import-", dir=data_dir) as directory:
        stage = Path(directory)
        unpack_archive(Path(archive_path), stage)
        config_file = stage / "config.json"
        config = json.loads(config_file.read_text(encoding="utf-8"))
        services = json.loads((stage / "alamp_config.json").read_text(encoding="utf-8"))
        if not isinstance(config, dict) or not isinstance(services, dict):
            raise ValueError("В архивах конфигурации ожидаются JSON-объекты")
        imported_config = Config.model_validate(_migrate_config(json.loads(json.dumps(config))))
        source_workspace = imported_config.agents.defaults.workspace
        if progress is not None:
            progress.accept(5)
        validate_database(stage / "sessions.sqlite3", len(_SCHEMA_MIGRATIONS), os.getenv("SQLITE_VEC_SO_PATH"))

        with _gateway_lock:
            paths = _gateway_paths
            if paths is None or _gateway_thread is None or not _gateway_thread.is_alive():
                raise RuntimeError("Шлюз ещё не готов")
            _retarget_import_config(config, paths[0])
            Config.model_validate(_migrate_config(json.loads(json.dumps(config))))
            config_file.write_text(json.dumps(config, ensure_ascii=False, indent=2), encoding="utf-8")
            retarget_session_workspaces(
                stage / "sessions.sqlite3", paths[0],
                [source_workspace, *_legacy_workspaces(data_dir)],
            )
            if progress is not None:
                progress.accept(6)
            _gateway_stop.set()
            _gateway_thread.join()
            try:
                replace_agent(stage, *paths)
            finally:
                _run_gateway(data_dir, check_bootstrap=False)


def _retarget_import_config(config, workspace) -> None:
    config.setdefault("agents", {}).setdefault("defaults", {})["workspace"] = str(workspace)
    config.setdefault("channels", {}).setdefault("websocket", {})["port"] = _WEB_PORT
    gateway = config.setdefault("gateway", {})
    gateway["port"] = _HEALTH_PORT
    gateway.pop("health", None)


def _legacy_workspaces(data_dir: str):
    from pathlib import Path

    old_files = Path(data_dir).parent.with_name("com.alamp.app") / "files"
    roots = {old_files}
    if old_files == Path("/data/data/com.alamp.app/files"):
        roots.add(Path("/data/user/0/com.alamp.app/files"))
    elif old_files == Path("/data/user/0/com.alamp.app/files"):
        roots.add(Path("/data/data/com.alamp.app/files"))
    return [root / ".nanobot" / "workspace" for root in roots]


def _extract_vec_so(data_dir: str) -> str | None:
    """Извлекает vec0.so из assets в приватную директорию приложения."""
    try:
        from com.chaquo.python import Python as ChaquopyPython
        platform = ChaquopyPython.getPlatform()
        app = platform.getApplication()
        assets = app.getAssets()

        vec_dir = os.path.join(data_dir, "sqlite-vec")
        os.makedirs(vec_dir, exist_ok=True)
        vec_path = os.path.join(vec_dir, "vec0.so")

        if not os.path.exists(vec_path):
            src = assets.open("sqlite-vec/arm64-v8a/vec0.so")
            try:
                dst = open(vec_path, "wb")
                try:
                    buf = bytearray(8192)
                    while True:
                        n = src.read(buf)
                        if n <= 0:
                            break
                        dst.write(buf[:n])
                finally:
                    dst.close()
            finally:
                src.close()
            os.chmod(vec_path, 0o755)

        os.environ["SQLITE_VEC_SO_PATH"] = vec_path
        return vec_path
    except Exception as e:
        log_path = os.path.join(data_dir, "nanobot", "vec_extract_error.log")
        os.makedirs(os.path.dirname(log_path), exist_ok=True)
        with open(log_path, "w") as f:
            f.write(f"vec extract failed: {e}\n{traceback.format_exc()}")
        return None



def _run_gateway(data_dir: str, *, check_bootstrap: bool = True):
    """Создаёт конфиг и запускает шлюз."""
    global _gateway_thread, _gateway_stop, _gateway_paths

    stop = threading.Event()
    _gateway_stop = stop
    _gateway_paths = None

    nanobot_dir = os.path.join(data_dir, "nanobot")
    config_path = os.path.join(nanobot_dir, "config.json")

    # Создаём директории
    os.makedirs(nanobot_dir, exist_ok=True)

    _extract_vec_so(data_dir)

    # Создаём конфиг, если его нет
    if not os.path.exists(config_path):
        config = {
            "agents": {
                "defaults": {
                    "model": "deepseek/deepseek-v4-flash",
                    "provider": "openrouter",
                    "max_tokens": 8192,
                    "context_window_tokens": 65536,
                    "temperature": 0.3,
                    "timezone": "Europe/Warsaw",
                    "timezone_mode": "manual",
                    "bot_name": "AndroidBot",
                    "bot_icon": "🤖"
                }
            },
            "model_presets": {
                "deepseek": {
                    "model": "deepseek/deepseek-v4-flash",
                    "provider": "openrouter",
                    "max_tokens": 8192,
                    "context_window_tokens": 262144,
                    "temperature": 0.3
                },
                "gemini": {
                    "model": "google/gemini-2.5-flash",
                    "provider": "openrouter",
                    "max_tokens": 8192,
                    "context_window_tokens": 262144,
                    "temperature": 0.3
                }
            },
            "providers": {
                "openrouter": {
                    "api_key": os.environ.get("OPENROUTER_API_KEY", "your-key"),
                    "api_base": "https://openrouter.ai/api/v1"
                }
            },
            "channels": {
                "websocket": {
                    "enabled": True,
                    "host": "localhost",
                    "port": _WEB_PORT,
                    "websocket_requires_token": False
                }
            },
            "gateway": {
                "port": _HEALTH_PORT,
                "heartbeat": {
                    "enabled": False
                }
            },
            "tools": {
                "exec": {
                    "enable": False
                },
                "mcpServers": {}
            }
        }
        with open(config_path, "w") as f:
            json.dump(config, f, indent=2)

    # --- Создаём alamp_config.json (темплейт, только OpenRouter с заглушкой) ---
    services_path = os.path.join(nanobot_dir, "alamp_config.json")
    if not os.path.exists(services_path):
        services = {
            "embedding_services": [
                {
                    "url": "https://openrouter.ai",
                    "api_key": "your-key",
                    "indexing": False,
                    "timeout": 5
                }
            ]
        }
        with open(services_path, "w") as f:
            json.dump(services, f, indent=2)
        print(f"Создан alamp_config.json (темплейт): {services_path}")

    # Запускаем шлюз в фоновом потоке
    def run():
        global _gateway_paths
        import traceback as _tb
        try:
            from pathlib import Path
            from agent_import import retarget_session_workspaces
            from nanobot.config.loader import load_config, set_config_path
            from nanobot.cli import gateway_runtime
            from nanobot.gateway.runtime import GatewayInstance
            from nanobot.session.location import SessionLocation
            from nanobot.session.sqlite_store import SqliteSessionStore

            # Устанавливаем SSL_CERT_FILE для Chaquopy (cacert.pem от certifi)
            _cert_path = os.path.join(data_dir, "chaquopy", "cacert.pem")
            if os.path.exists(_cert_path):
                os.environ["SSL_CERT_FILE"] = _cert_path
                os.environ["REQUESTS_CA_BUNDLE"] = _cert_path

            # Устанавливаем _current_config_path, чтобы load_provider_snapshot()
            # находил наш конфиг, а не создавал пустой Config() с model по умолчанию
            set_config_path(Path(config_path))

            config_obj = load_config(Path(config_path))
            websocket = getattr(config_obj.channels, "websocket", None)
            if isinstance(websocket, dict):
                websocket["port"] = _WEB_PORT
            # SessionManager использует тот же workspace и тот же алгоритм выбора каталога.
            location = SessionLocation(config_obj.workspace_path)
            memory_db_path = SqliteSessionStore(location.sessions_dir).path
            repaired_scopes = retarget_session_workspaces(
                memory_db_path, location.workspace, _legacy_workspaces(data_dir),
            )
            os.environ["MEMORY_DB_PATH"] = str(memory_db_path)
            os.environ["ALAMP_CONFIG_PATH"] = services_path
            # Создаём GatewayInstance с правильным config_path
            instance = GatewayInstance.resolve(config_path=config_path)

            # Дополнительная проверка перед запуском
            _test_log = os.path.join(nanobot_dir, "pre_gateway_test.log")
            with open(_test_log, "w") as _f:
                _f.write(f"config type: {type(config_obj)}\n")
                _f.write(f"memory database: {memory_db_path}\n")
                _f.write(f"repaired legacy session workspaces: {repaired_scopes}\n")
                _f.write(f"model_presets keys: {list(config_obj.model_presets.keys())}\n")
                _f.write(f"defaults.model_preset: {config_obj.agents.defaults.model_preset}\n")
                _f.write(f"defaults.model: {config_obj.agents.defaults.model}\n")
                try:
                    r = config_obj.resolve_preset()
                    _f.write(f"resolve_preset(): model={r.model}, provider={r.provider}\n")
                except Exception as e:
                    _f.write(f"resolve_preset() failed: {e}\n")
                    import traceback as _tb2
                    _f.write(_tb2.format_exc())

            runtime_class = gateway_runtime.GatewayRuntime

            class AndroidGatewayRuntime(runtime_class):
                async def wait_for_shutdown_request(self, shutdown_event):
                    global _gateway_paths
                    _gateway_paths = (location.workspace, memory_db_path, Path(config_path), Path(services_path))
                    while not stop.is_set():
                        await asyncio.sleep(0.2)
                    shutdown_event.set()

            # Шлюз запущен в потоке Android. Запрашиваем остановку внутри его event loop.
            gateway_runtime.GatewayRuntime = AndroidGatewayRuntime
            try:
                gateway_runtime._run_gateway(config_obj, port=_HEALTH_PORT, gateway_instance=instance)
            finally:
                gateway_runtime.GatewayRuntime = runtime_class
                _gateway_paths = None

        except Exception as e:
            tb = _tb.format_exc()
            log_path = os.path.join(nanobot_dir, "gateway_error.log")
            with open(log_path, "w") as f:
                f.write(f"Runtime Error: {e}\n{tb}")

    _gateway_thread = threading.Thread(target=run, daemon=True)
    _gateway_thread.start()
    if not check_bootstrap:
        return

    # Ждём запуска и проверяем bootstrap
    import time
    time.sleep(3)
    try:
        import urllib.request
        import json as _json
        req = urllib.request.Request(f"http://localhost:{_WEB_PORT}/webui/bootstrap")
        resp = urllib.request.urlopen(req, timeout=5)
        bootstrap = _json.loads(resp.read())
        log_path = os.path.join(nanobot_dir, "bootstrap_check.log")
        with open(log_path, "w") as f:
            f.write(_json.dumps(bootstrap, indent=2))
    except Exception as e:
        log_path = os.path.join(nanobot_dir, "bootstrap_check.log")
        with open(log_path, "w") as f:
            f.write(f"Bootstrap check failed: {e}")
