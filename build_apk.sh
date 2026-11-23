#!/usr/bin/env bash
# Сборка APK: wheel → gradle → (опционально) установка на телефон
set -euo pipefail

cd "$(dirname "$0")"

# Цвета
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

INSTALL=false
CLEAN=false

for arg in "$@"; do
    case "$arg" in
        --install|-i) INSTALL=true ;;
        --clean|-c)   CLEAN=true ;;
        --help|-h)
            echo "Использование: $0 [--install|-i] [--clean|-c]"
            echo "  --install  установить APK на телефон после сборки"
            echo "  --clean    чистая сборка (clean + assemble)"
            exit 0
            ;;
    esac
done

echo -e "${YELLOW}🔧 1. Сборка wheel...${NC}"
./build_wheel.sh

echo ""
echo -e "${YELLOW}📦 2. Сборка APK...${NC}"
export ANDROID_HOME="${ANDROID_HOME:-/home/ubuntu/Android/Sdk}"
export PATH=$PATH:$ANDROID_HOME/platform-tools

if [ "$CLEAN" = true ]; then
    ./gradlew clean assembleDebug 2>&1 | tail -5
else
    ./gradlew assembleDebug 2>&1 | tail -5
fi

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
    echo "❌ APK не найден: $APK"
    exit 1
fi

APK_SIZE=$(du -h "$APK" | cut -f1)
echo -e "${GREEN}✅ APK собран: $APK ($APK_SIZE)${NC}"

# Установка на телефон
if [ "$INSTALL" = true ]; then
    echo ""
    echo -e "${YELLOW}📱 3. Установка на телефон...${NC}"

    # Останавливаем приложение
    adb shell am force-stop com.alamp.app 2>/dev/null || true
    sleep 1

    # Устанавливаем
    adb install -r "$APK" 2>&1

    # Чистим кэш Chaquopy (БД и конфиг не трогаем)
    adb shell run-as com.alamp.app rm -rf /data/data/com.alamp.app/files/chaquopy 2>/dev/null || true

    # Запускаем
    adb shell am start -n com.alamp.app/.MainActivity 2>&1

    echo -e "${GREEN}✅ APK установлен и запущен${NC}"
fi

echo ""
echo -e "${GREEN}✨ Готово!${NC}"
