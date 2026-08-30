#!/bin/bash

# Скрипт для быстрого развертывания на сервере
# Использование: ./deploy.sh user@server:/opt/wb-bot

if [ -z "$1" ]; then
    echo "Использование: ./deploy.sh user@server:/path/to/destination"
    echo "Пример: ./deploy.sh root@192.168.1.100:/opt/wb-bot"
    exit 1
fi

DEST="$1"

echo "=== Сборка JAR файла ==="
./gradlew jar

if [ $? -ne 0 ]; then
    echo "Ошибка при сборке JAR!"
    exit 1
fi

JAR_FILE="build/libs/paser-rubli-otzivi.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "Ошибка: JAR файл не найден: $JAR_FILE"
    exit 1
fi

echo ""
echo "=== Копирование файлов на сервер ==="

# Создаем временную директорию для файлов
TEMP_DIR=$(mktemp -d)
echo "Временная директория: $TEMP_DIR"

# Копируем JAR
cp "$JAR_FILE" "$TEMP_DIR/paser-rubli-otzivi.jar"

# Копируем конфигурацию (если есть в корне)
if [ -f "app.properties" ]; then
    cp "app.properties" "$TEMP_DIR/"
else
    echo "Предупреждение: app.properties не найден в корне, копирую из src/main/resources"
    if [ -f "src/main/resources/app.properties" ]; then
        cp "src/main/resources/app.properties" "$TEMP_DIR/"
    fi
fi

# Копируем файлы категорий (если есть)
for file in Food.txt detyam.txt pidory.txt; do
    if [ -f "$file" ]; then
        cp "$file" "$TEMP_DIR/"
        echo "Скопирован: $file"
    fi
done

# Копируем скрипт запуска
if [ -f "start.sh" ]; then
    cp "start.sh" "$TEMP_DIR/"
    chmod +x "$TEMP_DIR/start.sh"
fi

echo ""
echo "=== Загрузка на сервер ==="
rsync -avz --progress "$TEMP_DIR/" "$DEST/"

if [ $? -eq 0 ]; then
    echo ""
    echo "=== Развертывание завершено! ==="
    echo ""
    echo "На сервере выполните:"
    echo "  cd $(dirname $DEST)"
    echo "  chmod +x start.sh"
    echo "  ./start.sh"
    echo ""
    echo "Или настройте systemd сервис (см. DEPLOYMENT.md)"
else
    echo "Ошибка при загрузке файлов!"
    exit 1
fi

# Очистка
rm -rf "$TEMP_DIR"

