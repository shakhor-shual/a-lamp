"""Запуск шлюза nanobot 0.4.1 на Android."""

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


def start_gateway(data_dir: str) -> str:
    """
    Запускает шлюз nanobot в фоновом потоке.
    """
    try:
        _run_gateway(data_dir)
        return "ok"
    except Exception as e:
        tb = traceback.format_exc()
        log_path = os.path.join(data_dir, "nanobot", "gateway_error.log")
        os.makedirs(os.path.dirname(log_path), exist_ok=True)
        with open(log_path, "w") as f:
            f.write(f"Error: {e}\n{tb}")
        return f"ERROR: {e}"


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


def _check_embeddings(nanobot_dir: str):
    """Проверяет, есть ли эмбеддинги в БД, и пишет лог."""
    db_path = os.path.join(nanobot_dir, "sessions.sqlite3")
    log_path = os.path.join(nanobot_dir, "embeddings_check.log")
    try:
        import sqlite3
        conn = sqlite3.connect(db_path)
        cur = conn.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
        tables = [r[0] for r in cur.fetchall()]
        info = {"tables": tables}
        for t in tables:
            cur = conn.execute(f'SELECT COUNT(*) FROM "{t}"')
            info[t] = cur.fetchone()[0]
        if "vec_chunks" in tables:
            cur = conn.execute("SELECT COUNT(*) FROM vec_chunks")
            info["vec_chunks_count"] = cur.fetchone()[0]
            cur = conn.execute("SELECT COUNT(DISTINCT k) FROM vec_chunks")
            info["vec_chunks_kinds"] = cur.fetchone()[0]
        conn.close()
        with open(log_path, "w") as f:
            import json
            f.write(json.dumps(info, indent=2))
    except Exception as e:
        with open(log_path, "w") as f:
            f.write(f"check failed: {e}\n{traceback.format_exc()}")


def _run_gateway(data_dir: str):
    """Создаёт конфиг и запускает шлюз."""

    nanobot_dir = os.path.join(data_dir, "nanobot")
    config_path = os.path.join(nanobot_dir, "config.json")
    workspace_dir = os.path.join(nanobot_dir, "workspace")

    # Создаём директории
    os.makedirs(nanobot_dir, exist_ok=True)
    os.makedirs(workspace_dir, exist_ok=True)

    # --- Извлекаем vec0.so для sqlite-vec ---
    vec_path = _extract_vec_so(data_dir)

    # --- Проверяем, есть ли эмбеддинги в БД ---
    _check_embeddings(nanobot_dir)

    # --- Путь к БД (всё ещё нужен для _check_embeddings и совместимости) ---
    os.environ["MEMORY_DB_PATH"] = os.path.join(nanobot_dir, "sessions.sqlite3")

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
                    "api_key": "your-key",
                    "api_base": "https://openrouter.ai/api/v1"
                }
            },
            "channels": {
                "websocket": {
                    "enabled": True,
                    "host": "127.0.0.1",
                    "port": 8765,
                    "websocket_requires_token": False
                }
            },
            "gateway": {
                "health": {
                    "enabled": True,
                    "host": "127.0.0.1",
                    "port": 18790
                },
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

    # Тест: загружаем конфиг и проверяем model_presets

    # Регистрируем инструменты alamp_memory напрямую (Chaquopy не поддерживает entry_points)
    try:
        from alamp_memory.tool import MemoryRecallTool, MemoryRecallContextTool
        from nanobot.agent.tools.registry import ToolRegistry
        _reg = ToolRegistry()
        _db = os.environ.get("MEMORY_DB_PATH", os.path.join(nanobot_dir, "sessions.sqlite3"))
        _reg.register(MemoryRecallTool(db_path=_db))
        _reg.register(MemoryRecallContextTool(db_path=_db))
    except Exception as e:
        _log_path = os.path.join(nanobot_dir, "alamp_register_error.log")
        with open(_log_path, "w") as _f:
            _f.write(f"alamp_memory register failed: {e}\n{traceback.format_exc()}")

    # Запускаем шлюз в фоновом потоке
    def run():
        import traceback as _tb
        try:
            from pathlib import Path
            from nanobot.config.loader import load_config, set_config_path
            from nanobot.cli.gateway_runtime import _run_gateway
            from nanobot.gateway.runtime import GatewayInstance

            # Устанавливаем SSL_CERT_FILE для Chaquopy (cacert.pem от certifi)
            _cert_path = os.path.join(data_dir, "chaquopy", "cacert.pem")
            if os.path.exists(_cert_path):
                os.environ["SSL_CERT_FILE"] = _cert_path
                os.environ["REQUESTS_CA_BUNDLE"] = _cert_path

            # Устанавливаем _current_config_path, чтобы load_provider_snapshot()
            # находил наш конфиг, а не создавал пустой Config() с model по умолчанию
            set_config_path(Path(config_path))

            config_obj = load_config(Path(config_path))
            # Создаём GatewayInstance с правильным config_path
            instance = GatewayInstance.resolve(config_path=config_path)

            # Дополнительная проверка перед запуском
            _test_log = os.path.join(nanobot_dir, "pre_gateway_test.log")
            with open(_test_log, "w") as _f:
                _f.write(f"config type: {type(config_obj)}\n")
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

            _run_gateway(config_obj, gateway_instance=instance)

        except Exception as e:
            tb = _tb.format_exc()
            log_path = os.path.join(nanobot_dir, "gateway_error.log")
            with open(log_path, "w") as f:
                f.write(f"Runtime Error: {e}\n{tb}")

    thread = threading.Thread(target=run, daemon=True)
    thread.start()

    # Ждём запуска и проверяем bootstrap
    import time
    time.sleep(3)
    try:
        import urllib.request
        import json as _json
        req = urllib.request.Request("http://127.0.0.1:8765/webui/bootstrap")
        resp = urllib.request.urlopen(req, timeout=5)
        bootstrap = _json.loads(resp.read())
        log_path = os.path.join(nanobot_dir, "bootstrap_check.log")
        with open(log_path, "w") as f:
            f.write(_json.dumps(bootstrap, indent=2))
    except Exception as e:
        log_path = os.path.join(nanobot_dir, "bootstrap_check.log")
        with open(log_path, "w") as f:
            f.write(f"Bootstrap check failed: {e}")
