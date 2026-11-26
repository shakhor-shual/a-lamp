#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 || ${1:-} == -* || -z ${1:-} || ! ${2:-} =~ ^[[:alnum:]_][[:alnum:]_.-]*$ ]]; then
    echo "Использование: $0 ADB_SERIAL ИМЯ_СОСЕДНЕЙ_ПАПКИ" >&2
    exit 2
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source_dir="$script_dir/$2"
package=com.ailamp.app
files=(config.json alamp_config.json SOUL.md AGENTS.md USER.md sessions.sqlite3)

for file in "${files[@]}"; do
    if [[ ! -f "$source_dir/$file" || ! -s "$source_dir/$file" ]]; then
        echo "Нет файла или файл пуст: $source_dir/$file" >&2
        exit 1
    fi
done

workspace_selector=$(python3 - "$source_dir" <<'PY'
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
with (source / "alamp_config.json").open() as config:
    json.load(config)
with (source / "config.json").open() as config:
    workspace = json.load(config).get("agents", {}).get("defaults", {}).get(
        "workspace", "~/.nanobot/workspace"
    )
if not isinstance(workspace, str) or (
    workspace != "~/.nanobot/workspace" and not workspace.startswith("/")
) or any(ord(char) < 32 for char in workspace):
    sys.exit("В config.json ожидается workspace по умолчанию или абсолютный путь на устройстве.")
print(workspace)
PY
)

adb_bin=${ADB_CMD:-adb}
if [[ -z ${ADB_CMD:-} ]] && ! command -v adb >/dev/null; then
    adb_bin="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
fi
adb=("$adb_bin" -s "$1")

# ADB передаёт команду строкой в оболочку Android.
quote_remote() {
    local value=${1//\'/\'\\\'\'}
    printf "'%s'" "$value"
}

workspace_id=$("${adb[@]}" shell -T "run-as $package sh -s -- $(quote_remote "$workspace_selector")" <<'REMOTE'
set -eu
selector=$1
if [ "$selector" != '~/.nanobot/workspace' ]; then
    selector=$(cd "$selector" && pwd -P)
fi

match=
for marker in files/nanobot/sessions/*/.workspace; do
    [ -f "$marker" ] || continue
    directory=${marker%/.workspace}
    id=${directory##*/}
    case "$id" in *[!0-9a-f]*) continue ;; esac
    [ "${#id}" -eq 32 ] || continue
    workspace=$(cat "$marker")
    case "$selector" in
        '~/.nanobot/workspace')
            case "$workspace" in /*/.nanobot/workspace) ;; *) continue ;; esac
            ;;
        *) [ "$workspace" = "$selector" ] || continue ;;
    esac
    [ -f "$workspace/.nanobot/workspace-id" ] || continue
    [ "$(cat "$workspace/.nanobot/workspace-id")" = "$id" ] || continue
    if [ -n "$match" ]; then
        echo "Найдено несколько подходящих workspace." >&2
        exit 1
    fi
    match=$id
done
if [ -z "$match" ]; then
    echo "Workspace Nanobot не найден. Сначала запустите приложение с нужным workspace." >&2
    exit 1
fi
printf '%s\n' "$match"
REMOTE
)

if [[ ! $workspace_id =~ ^[0-9a-f]{32}$ ]]; then
    echo "Устройство вернуло неожиданный идентификатор workspace." >&2
    exit 1
fi

stage=$("${adb[@]}" shell -T "run-as $package mktemp -d files/nanobot/.deploy.XXXXXXXX")
if [[ ! $stage =~ ^files/nanobot/\.deploy\.[[:alnum:]]+$ ]]; then
    echo "Устройство вернуло неожиданный путь временного каталога." >&2
    exit 1
fi
trap '"${adb[@]}" shell -T "run-as $package rm -rf $stage" >/dev/null' EXIT

for file in "${files[@]}"; do
    echo "Копирую $file"
    remote_size=$("${adb[@]}" shell -T "run-as $package sh -c 'umask 077; cat > $stage/$file && wc -c < $stage/$file'" < "$source_dir/$file")
    if [[ ${remote_size//[[:space:]]/} != "$(stat -c %s -- "$source_dir/$file")" ]]; then
        echo "Размер $file на устройстве не совпадает с исходным." >&2
        exit 1
    fi
done

"${adb[@]}" shell -T "am force-stop $package"
"${adb[@]}" shell -T "run-as $package sh -s -- $stage $workspace_id" <<'REMOTE'
set -eu
stage=$1
id=$2
nanobot=files/nanobot
sessions_dir="$nanobot/sessions/$id"
workspace=$(cat "$sessions_dir/.workspace")
[ "$(cat "$workspace/.nanobot/workspace-id")" = "$id" ]

for file in config.json alamp_config.json; do
    mv -f "$stage/$file" "$nanobot/$file"
done
for file in SOUL.md AGENTS.md USER.md; do
    mv -f "$stage/$file" "$workspace/$file"
done

database="$sessions_dir/sessions.sqlite3"
mv -f "$stage/sessions.sqlite3" "$database"
rm -f "$database-wal" "$database-shm"
printf 'Workspace: %s\nБаза: %s\n' "$workspace" "$database"
REMOTE

echo "Данные установлены. Приложение можно запустить."
