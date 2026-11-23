#!/usr/bin/env bash
# Развёртывание данных (БД, конфиги, промпты) на телефон одной командой
set -euo pipefail

cd "$(dirname "$0")"

DATA="app/data"
PKG="com.alamp.app"
NANOBOT_DIR="/data/data/$PKG/files/nanobot"

ADB="/home/ubuntu/Android/Sdk/platform-tools/adb -s 10bcb36a"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${YELLOW}📦 Развёртывание данных на телефон...${NC}"

# 1. Копируем всё на sdcard одной пачкой
echo "   → копирование на sdcard..."
$ADB push "$DATA" /sdcard/alamp_deploy/ > /dev/null 2>&1

# 2. Разворачиваем через run-as (по одной команде)
echo "   → развёртывание в nanobot..."

deploy_file() {
    local src="$1"
    local dst="$2"
    $ADB shell "cat '$src' | run-as '$PKG' sh -c 'cat > \"$dst\" && chmod 600 \"$dst\"'" 2>/dev/null
}

deploy_mkdir() {
    $ADB shell run-as "$PKG" mkdir -p "$1" 2>/dev/null
}

deploy_rm() {
    $ADB shell run-as "$PKG" rm -rf "$1" 2>/dev/null || true
}

# БД
echo "   → БД..."
deploy_file /sdcard/alamp_deploy/sessions.sqlite3 /data/data/com.alamp.app/files/nanobot/sessions.sqlite3 && echo "   ✅ БД: sessions.sqlite3"

# Конфиги
echo "   → конфиги..."
deploy_file /sdcard/alamp_deploy/config.json /data/data/com.alamp.app/files/nanobot/config.json && echo "   ✅ Конфиг: config.json"
deploy_file /sdcard/alamp_deploy/alamp_config.json /data/data/com.alamp.app/files/nanobot/alamp_config.json && echo "   ✅ Конфиг: alamp_config.json"

# Промпты → workspace/
echo "   → промпты..."
deploy_mkdir /data/data/com.alamp.app/files/nanobot/workspace
for f in SOUL.md AGENTS.md USER.md; do
    deploy_file "/sdcard/alamp_deploy/prompts/$f" "/data/data/com.alamp.app/files/nanobot/workspace/$f" && echo "   ✅ Промпт: $f"
done

# Чистим кэш Chaquopy
deploy_rm /data/data/com.alamp.app/files/nanobot/chaquopy
echo "   ✅ Кэш Chaquopy очищен"

# 3. Чистим sdcard
$ADB shell rm -rf /sdcard/alamp_deploy/ > /dev/null 2>&1

echo ""
echo -e "${GREEN}✅ Данные развёрнуты!${NC}"
echo "   Перезапусти приложение, чтобы изменения вступили в силу."
