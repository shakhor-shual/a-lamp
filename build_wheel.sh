#!/usr/bin/env bash
# Сборка alamp_memory wheel из исходников в app/src/main/python/alamp_memory/
set -euo pipefail

cd "$(dirname "$0")"
PROJECT_ROOT="$PWD"

SRC="app/src/main/python/alamp_memory"
DIST_INFO="alamp_memory-0.1.0.dist-info"
WHEEL_OUT="$PROJECT_ROOT/app/alamp_memory-0.1.0-py3-none-any.whl"

# Временная директория для сборки wheel
BUILD_DIR=$(mktemp -d)
trap 'rm -rf "$BUILD_DIR"' EXIT

# Копируем исходники
mkdir -p "$BUILD_DIR/alamp_memory"
cp "$SRC"/*.py "$BUILD_DIR/alamp_memory/"

# Копируем dist-info (шаблон)
mkdir -p "$BUILD_DIR/$DIST_INFO"
cat > "$BUILD_DIR/$DIST_INFO/METADATA" << 'META'
Metadata-Version: 2.1
Name: alamp-memory
Version: 0.1.0
Summary: Search nanobot SQLite memory (FTS5 + vector)
Requires-Python: >=3.11
Requires-Dist: httpx>=0.27
META

cat > "$BUILD_DIR/$DIST_INFO/WHEEL" << 'WHEEL'
Wheel-Version: 1.0
Generator: alamp-build
Root-Is-Purelib: true
Tag: py3-none-any
WHEEL

cat > "$BUILD_DIR/$DIST_INFO/entry_points.txt" << 'EP'
[alamp_memory.tools]
memory_recall = alamp_memory.tool:MemoryRecallTool
memory_recall_context = alamp_memory.tool:MemoryRecallContextTool
EP

# Собираем RECORD
cd "$BUILD_DIR"
python3 -c "
import hashlib, os
records = []
for root, dirs, files in os.walk('.'):
    for f in sorted(files):
        path = os.path.join(root, f)
        if path == './$DIST_INFO/RECORD':
            continue
        size = os.path.getsize(path)
        with open(path, 'rb') as fp:
            h = hashlib.sha256(fp.read()).hexdigest()
        records.append(f'{path[2:]},sha256={h},{size}')
records.append('$DIST_INFO/RECORD,,')
with open('$DIST_INFO/RECORD', 'w') as f:
    f.write('\n'.join(records) + '\n')
"

# Упаковываем wheel
rm -f "$WHEEL_OUT"
zip -r "$WHEEL_OUT" . > /dev/null
echo "✅ Wheel собран: $WHEEL_OUT ($(du -h "$WHEEL_OUT" | cut -f1))"
