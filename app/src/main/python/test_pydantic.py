"""Тест native wheels на Android через Chaquopy."""

import sys


def run_all() -> str:
    lines = []
    lines.append(f"Python: {sys.version}")

    # 1. pydantic_core
    try:
        import pydantic_core
        lines.append(f"pydantic_core: OK — {pydantic_core.__version__}")
    except Exception as e:
        lines.append(f"pydantic_core: FAIL — {e}")
        return "\n".join(lines)

    # 2. pydantic
    try:
        import pydantic
        lines.append(f"pydantic: OK — {pydantic.__version__}")
    except Exception as e:
        lines.append(f"pydantic: FAIL — {e}")
        return "\n".join(lines)

    # 3. BaseModel
    try:
        from pydantic import BaseModel

        class TestModel(BaseModel):
            name: str
            count: int

        m = TestModel(name="test", count=42)
        lines.append(f"BaseModel: OK — {m.model_dump()}")
    except Exception as e:
        lines.append(f"BaseModel: FAIL — {e}")
        return "\n".join(lines)

    # 4. jiter (быстрый JSON)
    try:
        import jiter
        data = jiter.from_json(b'{"a":1,"b":[2,3]}')
        lines.append(f"jiter: OK — {data}")
    except Exception as e:
        lines.append(f"jiter: FAIL — {e}")

    # 5. tiktoken (токенизатор)
    try:
        import tiktoken
        lines.append(f"tiktoken: OK — {tiktoken.__version__}")
    except Exception as e:
        lines.append(f"tiktoken: FAIL — {e}")

    # 6. watchfiles (наблюдение за файлами)
    try:
        import watchfiles
        lines.append(f"watchfiles: OK — {watchfiles.__version__}")
    except Exception as e:
        lines.append(f"watchfiles: FAIL — {e}")

    # 7. rapidfuzz (нечёткое сравнение)
    try:
        from rapidfuzz import fuzz
        score = fuzz.ratio("hello world", "hello android")
        lines.append(f"rapidfuzz: OK — ratio={score:.1f}")
    except Exception as e:
        lines.append(f"rapidfuzz: FAIL — {e}")

    # 8. regex (нужен tiktoken)
    try:
        import regex
        m = regex.match(r"\d+", "123abc")
        lines.append(f"regex: OK — matched={m.group() if m else 'none'}")
    except Exception as e:
        lines.append(f"regex: FAIL — {e}")

    # 9. cffi (нужен cryptography)
    try:
        import cffi
        lines.append(f"cffi: OK — {cffi.__version__}")
    except Exception as e:
        lines.append(f"cffi: FAIL — {e}")

    # 10. cryptography
    try:
        import cryptography
        lines.append(f"cryptography: OK — {cryptography.__version__}")
        from cryptography.fernet import Fernet
        key = Fernet.generate_key()
        f = Fernet(key)
        token = f.encrypt(b"hello android")
        decrypted = f.decrypt(token)
        lines.append(f"  Fernet: OK — {decrypted.decode()}")
    except Exception as e:
        lines.append(f"cryptography: FAIL — {e}")

    # 11. pillow
    try:
        from PIL import Image
        import io
        img = Image.new("RGB", (10, 10), color="red")
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        lines.append(f"pillow: OK — PNG={len(buf.getvalue())}b")
        buf = io.BytesIO()
        img.save(buf, format="JPEG")
        lines.append(f"  JPEG: OK — {len(buf.getvalue())}b")
    except Exception as e:
        lines.append(f"pillow: FAIL — {e}")

    # 12. primp (HTTP)
    try:
        import primp
        lines.append(f"primp: OK — {primp.__version__}")
    except Exception as e:
        lines.append(f"primp: FAIL — {e}")

    # 13. setproctitle
    try:
        import setproctitle
        lines.append(f"setproctitle: OK — {setproctitle.__version__}")
    except Exception as e:
        lines.append(f"setproctitle: FAIL — {e}")

    # 14. rpds (нужен jsonschema)
    try:
        import rpds
        lines.append(f"rpds: OK — imported")
    except Exception as e:
        lines.append(f"rpds: FAIL — {e}")

    # 15. jsonschema (через rpds)
    try:
        import jsonschema
        lines.append(f"jsonschema: OK — {jsonschema.__version__}")
        v = jsonschema.validate({"name": "test"}, {"type": "object", "properties": {"name": {"type": "string"}}})
        lines.append(f"  validate: OK")
    except Exception as e:
        lines.append(f"jsonschema: FAIL — {e}")

    # 16. mcp (через jsonschema → rpds)
    try:
        import mcp
        lines.append(f"mcp: OK — imported")
    except Exception as e:
        lines.append(f"mcp: FAIL — {e}")

    return "\n".join(lines)
