# Инструкция по развертыванию бота на сервере

## 1. Сборка JAR файла

### На локальной машине (Windows):

```powershell
# Перейти в директорию проекта
cd E:\ProjectJava\paser-rubli-otzivi

# Собрать JAR со всеми зависимостями
.\gradlew.bat jar

# JAR файл будет в: build\libs\paser-rubli-otzivi.jar
```

### На Linux/Mac:

```bash
cd /path/to/paser-rubli-otzivi
./gradlew jar
# JAR файл будет в: build/libs/paser-rubli-otzivi.jar
```

## 2. Файлы для переноса на сервер

Создайте на сервере директорию (например, `/opt/wb-bot` или `~/wb-bot`) и скопируйте:

### Обязательные файлы:

1. **JAR файл:**
   - `build/libs/paser-rubli-otzivi.jar` → `paser-rubli-otzivi.jar`

2. **Конфигурация:**
   - `src/main/resources/app.properties` → `app.properties` (отредактируйте для продакшена!)

3. **Файлы категорий (если используются):**
   - `Food.txt` → `Food.txt`
   - `detyam.txt` → `detyam.txt`

4. **Блок-лист продавцов (если есть):**
   - `pidory.txt` → `pidory.txt`

### Структура на сервере:

```
/opt/wb-bot/
├── paser-rubli-otzivi.jar
├── app.properties
├── Food.txt
├── detyam.txt
├── pidory.txt
├── start.sh
└── data/                    # Создастся автоматически
    └── wb-bot.db           # Создастся автоматически
```

## 3. Настройка app.properties на сервере

**ВАЖНО:** Отредактируйте `app.properties` перед запуском:

```properties
# Telegram бот
bot.username=your_bot_username
bot.token=your_bot_token

# Потоки (настройте под ваш сервер)
threads.catalog=128
threads.product=1024
threads.telegram=2

# HTTP настройки
http.timeout.seconds=5
http.max.retries=3
http.base.backoff.millis=500
http.rate.limit.millis=2

# Wildberries
wb.maxPagesPerCategory=100
wb.catalogRefreshSeconds=60
wb.clicks=
# ВАЖНО: Обновите cookies перед запуском!
wb.staticCookies=_wbauid=...;x_wbaas_token=...

# SQLite
sqlite.path=data/wb-bot.db
sqlite.queue.capacity=2000
sqlite.batch.size=100
```

## 4. Установка Java на сервере

Бот требует Java 11 или выше:

```bash
# Ubuntu/Debian
sudo apt update
sudo apt install openjdk-17-jdk

# Проверка версии
java -version
```

## 5. Установка Firefox для Selenium (опционально)

Если хотите использовать Selenium для получения cookies:

```bash
# Ubuntu/Debian
sudo apt install firefox

# Проверка
firefox --version
```

Если Firefox не установлен, бот будет использовать fallback HTTP метод.

## 6. Создание скрипта запуска

Создайте файл `start.sh`:

```bash
#!/bin/bash

# Директория с ботом
BOT_DIR="/opt/wb-bot"
cd "$BOT_DIR"

# JVM параметры (настройте под ваш сервер)
JAVA_OPTS="-Xms512m -Xmx2048m -XX:+UseG1GC"

# Запуск
java $JAVA_OPTS -jar paser-rubli-otzivi.jar
```

Сделайте скрипт исполняемым:

```bash
chmod +x start.sh
```

## 7. Запуск как systemd сервис (рекомендуется)

Создайте файл `/etc/systemd/system/wb-bot.service`:

```ini
[Unit]
Description=Wildberries Parser Telegram Bot
After=network.target

[Service]
Type=simple
User=your_user
WorkingDirectory=/opt/wb-bot
ExecStart=/usr/bin/java -Xms512m -Xmx2048m -XX:+UseG1GC -jar /opt/wb-bot/paser-rubli-otzivi.jar
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

Активация сервиса:

```bash
# Перезагрузить конфигурацию systemd
sudo systemctl daemon-reload

# Включить автозапуск
sudo systemctl enable wb-bot

# Запустить сервис
sudo systemctl start wb-bot

# Проверить статус
sudo systemctl status wb-bot

# Просмотр логов
sudo journalctl -u wb-bot -f
```

## 8. Запуск через screen/tmux (альтернатива)

Если не хотите использовать systemd:

```bash
# Установка screen
sudo apt install screen

# Создание сессии
screen -S wb-bot

# Запуск бота
cd /opt/wb-bot
./start.sh

# Отключиться: Ctrl+A, затем D
# Подключиться обратно: screen -r wb-bot
```

## 9. Проверка работы

1. **Проверьте логи:**
   ```bash
   # Если через systemd
   sudo journalctl -u wb-bot -f
   
   # Если через screen
   screen -r wb-bot
   ```

2. **Отправьте команду боту в Telegram:**
   - `/status` - проверить статус
   - `/run` - запустить парсинг
   - `/help` - список команд

3. **Проверьте базу данных:**
   ```bash
   sqlite3 /opt/wb-bot/data/wb-bot.db "SELECT COUNT(*) FROM products;"
   ```

## 10. Обновление бота

1. Остановите сервис:
   ```bash
   sudo systemctl stop wb-bot
   ```

2. Создайте резервную копию БД:
   ```bash
   cp /opt/wb-bot/data/wb-bot.db /opt/wb-bot/data/wb-bot.db.backup
   ```

3. Замените JAR файл:
   ```bash
   cp paser-rubli-otzivi.jar /opt/wb-bot/
   ```

4. Запустите сервис:
   ```bash
   sudo systemctl start wb-bot
   ```

## 11. Мониторинг

### Проверка использования памяти:
```bash
ps aux | grep java
```

### Проверка размера БД:
```bash
du -h /opt/wb-bot/data/wb-bot.db
```

### Проверка логов на ошибки:
```bash
sudo journalctl -u wb-bot --since "1 hour ago" | grep ERROR
```

## 12. Решение проблем

### Бот не запускается:
- Проверьте версию Java: `java -version`
- Проверьте наличие app.properties
- Проверьте логи: `sudo journalctl -u wb-bot -n 50`

### Бот не получает товары:
- Проверьте cookies в app.properties (могут устареть)
- Проверьте интернет-соединение
- Проверьте логи на ошибки HTTP

### Высокое использование памяти:
- Уменьшите `threads.catalog` и `threads.product` в app.properties
- Увеличьте `-Xmx` в JVM параметрах

### Бот падает:
- Проверьте логи: `sudo journalctl -u wb-bot -n 100`
- Убедитесь, что есть место на диске: `df -h`
- Проверьте права доступа к файлам: `ls -la /opt/wb-bot/`
