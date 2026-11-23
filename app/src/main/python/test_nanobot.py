"""Тест nanobot 0.4.1 на Android: загрузка конфигурации."""

import sys
import os


def run_all() -> str:
    lines = []
    lines.append(f"Python: {sys.version}")

    # 1. import nanobot
    try:
        import nanobot
        lines.append(f"nanobot: OK — {nanobot.__version__}")
    except Exception as e:
        lines.append(f"nanobot: FAIL — {e}")
        return "\n".join(lines)

    # 2. Config schema
    try:
        from nanobot.config.schema import Config
        lines.append(f"Config schema: OK — imported")
    except Exception as e:
        lines.append(f"Config schema: FAIL — {e}")
        return "\n".join(lines)

    # 3. load_config
    try:
        from nanobot.config.loader import load_config
        lines.append(f"load_config: OK — imported")
    except Exception as e:
        lines.append(f"load_config: FAIL — {e}")

    # 4. get_data_dir
    try:
        from nanobot.config.paths import get_data_dir
        lines.append(f"get_data_dir: OK — imported")
    except Exception as e:
        lines.append(f"get_data_dir: FAIL — {e}")

    # 5. Проверяем pydantic
    try:
        from pydantic import BaseModel

        class TestModel(BaseModel):
            name: str
            value: int

        m = TestModel(name="test", value=42)
        lines.append(f"pydantic BaseModel: OK — {m.model_dump()}")
    except Exception as e:
        lines.append(f"pydantic BaseModel: FAIL — {e}")

    return "\n".join(lines)
