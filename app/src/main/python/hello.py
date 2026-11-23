import sys

def hello():
    lines = ["Hello from Python inside Android!"]
    
    # Тест pydantic-core
    try:
        import pydantic_core
        lines.append(f"pydantic_core version: {pydantic_core.__version__}")
        
        # Проверка работы
        v = pydantic_core.SchemaValidator({"type": "int"})
        result = v.validate_python("42")
        lines.append(f"SchemaValidator test: {result}")
    except Exception as e:
        import traceback
        tb = traceback.format_exc()
        lines.append(f"pydantic_core ERROR: {e}")
        lines.append(tb)
    
    return "\n".join(lines)

def add(a, b):
    return a + b
