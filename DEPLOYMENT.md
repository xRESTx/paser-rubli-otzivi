# Инструкция по развертыванию на сервере

## Требования к серверу

### 1. Java
- **Java 17 или выше** (рекомендуется Java 21+)
- Проверить версию: `java -version`

### 2. SQLite
- **SQLite НЕ нужно устанавливать отдельно!**
- Драйвер SQLite уже включен в JAR файл (`sqlite-jdbc`)
- БД создается автоматически при первом запуске

### 3. Firefox (опционально, для получения cookies)
- Если Firefox установлен, бот будет использовать его для получения cookies
- Если Firefox не установлен, бот будет использовать fallback метод

## Сборка JAR файла

### Вариант 1: Сборка через Gradle (рекомендуется)

```bash
# Сборка JAR со всеми зависимостями (fat JAR)
./gradlew build

# Или на Windows
gradlew.bat build
```

JAR файл будет создан в: `build/libs/paser-rubli-otzivi.jar`

### Вариант 2: Создание fat JAR вручную

Если нужно создать JAR со всеми зависимостями, добавьте в `build.gradle`:

```gradle
jar {
    manifest {
        attributes 'Main-Class': 'org.example.MyDualBot'
    }
    from {
        configurations.runtimeClasspath.collect { it.isDirectory() ? it : zipTree(it) }
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

Затем:
```bash
./gradlew jar
```

## Подготовка файлов для сервера

### 1. Необходимые файлы:

**Обязательные:**
```
paser-rubli-otzivi.jar          # Собранный JAR файл
app.properties                   # Конфигурация (из src/main/resources/app.properties)
```

**Опциональные (нужны только если используете соответствующие каналы):**
```
Food.txt                         # Список URL категорий еды (только если используете канал FOOD)
detyam.txt                       # Список URL категорий для детей (только если используете канал CHILDREN)
pidory.txt                       # Блок-лист продавцов (создается автоматически при использовании /pidory)
```

**Важно:** Если файлов `Food.txt` и `detyam.txt` нет, соответствующие каналы просто не будут использоваться. Это нормально.

### 2. Структура на сервере:

```
/home/user/wb-bot/
├── paser-rubli-otzivi.jar      # Обязательно
├── app.properties               # Обязательно
├── Food.txt                     # Опционально (только для канала FOOD)
├── detyam.txt                   # Опционально (только для канала CHILDREN)
├── pidory.txt                   # Опционально (создается автоматически)
└── data/                        # Создается автоматически
    └── wb-bot.db                # SQLite база данных (создается автоматически)
```

## Настройка app.properties

Скопируйте `src/main/resources/app.properties` на сервер и настройте:

```properties
# Токен Telegram бота
bot.username=your_bot_username
bot.token=your_bot_token

# Настройки потоков (можно оставить по умолчанию)
threads.catalog=256
threads.product=512
threads.telegram=2

# HTTP настройки
http.timeout.seconds=5
http.max.retries=3
http.base.backoff.millis=500
http.rate.limit.millis=2

# Wildberries настройки
wb.maxPagesPerCategory=100
wb.catalogRefreshSeconds=60
wb.clicks=
wb.staticCookies=your_cookies_here

# SQLite (путь относительно директории запуска)
sqlite.path=data/wb-bot.db
sqlite.queue.capacity=2000
sqlite.batch.size=100
```

## Запуск на сервере

### Простой запуск:

```bash
java -jar paser-rubli-otzivi.jar
```

### Запуск с настройками памяти:

```bash
java -Xms512m -Xmx2g -jar paser-rubli-otzivi.jar
```

### Запуск в фоне (Linux):

```bash
nohup java -Xms512m -Xmx2g -jar paser-rubli-otzivi.jar > bot.log 2>&1 &
```

### Запуск как systemd service (Linux):

Создайте файл `/etc/systemd/system/wb-bot.service`:

```ini
[Unit]
Description=Wildberries Bot
After=network.target

[Service]
Type=simple
User=your_user
WorkingDirectory=/home/user/wb-bot
ExecStart=/usr/bin/java -Xms512m -Xmx2g -jar /home/user/wb-bot/paser-rubli-otzivi.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Затем:
```bash
sudo systemctl daemon-reload
sudo systemctl enable wb-bot
sudo systemctl start wb-bot
sudo systemctl status wb-bot
```

## Проверка работы

1. Проверьте логи: `tail -f bot.log` или `journalctl -u wb-bot -f`
2. Отправьте боту команду `/help` в Telegram
3. Проверьте, что создалась папка `data/` и файл `wb-bot.db`

## Команды бота

- `/run` - Запустить сканер
- `/stop` - Остановить сканер
- `/status` - Проверить статус сканера
- `/pidory` - Добавить продавца в блок-лист
- `/clear` - Проверить товары из БД и обновить акции
- `/help` - Показать список команд

## Важные замечания

1. **SQLite не требует установки** - драйвер встроен в JAR, БД создается автоматически
2. **База данных создается автоматически** при первом запуске в папке `data/`
3. **Файлы Food.txt и detyam.txt** - опциональны, нужны только если используете каналы FOOD и CHILDREN
   - Если файлов нет → каналы просто не используются (это нормально)
4. **pidory.txt** - создается автоматически при добавлении продавцов через `/pidory`
5. **app.properties** - должен быть в той же директории, что и JAR, или в classpath

## Устранение проблем

### Бот не запускается:
- Проверьте версию Java: `java -version`
- Проверьте наличие `app.properties`
- Проверьте логи на ошибки

### База данных не создается:
- Проверьте права на запись в директорию
- Проверьте путь в `app.properties`: `sqlite.path=data/wb-bot.db`

### Firefox не найден:
- Это не критично, бот будет использовать fallback метод
- Или установите Firefox на сервере

