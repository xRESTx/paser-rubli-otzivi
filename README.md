# paser-rubli-otzivi

Java-бот для Telegram, который мониторит промо Wildberries "Рубли за отзывы", ищет товары с выгодным соотношением вознаграждения за отзыв к цене товара и отправляет найденные позиции в Telegram-чаты/топики.

Проект выглядит как учебный/личный инструмент для автоматизированного анализа открытых и внутренних JSON/API-ответов Wildberries. Перед использованием проверьте правила Wildberries, Telegram и применимое законодательство: автоматизированные запросы, обход ограничений и использование пользовательских cookies могут нарушать условия сервиса.

## Что делает бот

- Загружает дерево категорий акции из `https://static-basket-01.wbbasket.ru/vol0/data/promotions/rubli-za-otzyvy-v3.json`.
- Формирует запросы к catalog/card API Wildberries по категориям и страницам.
- Парсит артикул, название, цену, количество, cashback/feedback points.
- Считает процент выгоды: `cashback / price`.
- Раскладывает найденные товары по очередям и Telegram-направлениям:
  - `>= 100%` - `sent_articles100.txt`
  - `>= 90%` - `sent_articles90.txt`
  - `>= 80%` - `sent_articles80.txt`
  - `> 65%` - бесплатный чат, `sent_articlesFree.txt`
  - отдельные правила для крупных выплат, food/detyam/community-каналов
- Ведет файлы уже отправленных артикулов `sent_articles*.txt`, чтобы не слать дубли с близким процентом выгоды.
- Поддерживает ротацию cookies и прокси для Wildberries-запросов.
- Управляется через Telegram-команды администратора.

## Технологии

- Java 17
- Gradle 8.14 wrapper
- Telegram:
  - `com.github.pengrad:java-telegram-bot-api`
  - `org.telegram:telegrambots`
- HTTP/парсинг:
  - Jsoup
  - Gson
- Кэширование:
  - Caffeine
- Логи:
  - SLF4J + Logback
- Тесты:
  - JUnit 5

## Структура проекта

```text
src/main/java/org/example/MyDualBot.java        # основной Telegram-бот и парсер
src/main/java/org/example/WbRpsAutotune.java    # утилита подбора нагрузки/RPS
src/main/java/org/example/config/BotConfig.java # загрузка bot.properties
src/main/java/org/example/http/                 # cookies, proxy pool, WB HTTP-клиент
src/main/java/org/example/jsonmodel/            # модели JSON-ответов WB
src/main/resources/bot.properties               # пример/ресурс конфигурации
src/main/resources/logback.xml                  # запись логов в parser.log
src/test/java/org/example/                      # интеграционные и unit-тесты
Food.txt                                        # список категорий food
detyam.txt                                      # список категорий detyam
pidory.txt                                      # блок-лист продавцов
sent_articles*.txt                              # состояние отправленных артикулов
cookies.txt                                     # cookies Wildberries
proxies.txt                                     # список прокси
deploy.sh                                       # сборка и rsync-деплой
reboot_server.sh                                # reboot-скрипт для cron
```

## Требования

- JDK 17+
- Доступ к Telegram Bot API
- Telegram bot token и username
- Актуальные cookies Wildberries
- Опционально: HTTP/SOCKS-прокси для Telegram и отдельные прокси для WB-запросов

## Настройка

Конфигурация загружается классом `BotConfig` в таком порядке:

1. `src/main/resources/bot.properties` из classpath
2. `./bot.properties` из рабочей директории, если файл существует

Лучше хранить реальные секреты в `bot.properties` в рабочей директории на сервере, а не в ресурсах проекта.

Пример `bot.properties`:

```properties
bot.username=your_bot_username
bot.token=123456:telegram_bot_token

wb.cookies.file=cookies.txt
wb.proxies.file=proxies.txt
wb.proxy.username=
wb.proxy.password=
wb.proxy.required=false

# Опционально, если Telegram API недоступен напрямую
# telegram.proxy.type=HTTP
# telegram.proxy.host=127.0.0.1
# telegram.proxy.port=8080
# telegram.proxy.username=
# telegram.proxy.password=
```

### Cookies

Файл `cookies.txt` поддерживает несколько форматов:

```text
Cookie: name=value; name2=value2
```

или:

```text
name=value
name2=value2
```

Если в файле несколько строк с `Cookie:` или строк с `;`, они считаются отдельными cookie-наборами и ротируются. Если указаны отдельные пары `name=value`, они объединяются в один набор.

### Прокси

Файл `proxies.txt`:

```text
host:port
host:port:username:password
```

Если логин/пароль не указаны в строке, используются `wb.proxy.username` и `wb.proxy.password`. По умолчанию включена health-check проверка прокси. Ее можно отключить системным свойством:

```bash
-Dwb.proxyHealthCheck=false
```

## Запуск

На Windows:

```powershell
.\gradlew.bat runBot
```

На Linux/macOS:

```bash
./gradlew runBot
```

Собрать fat JAR:

```bash
./gradlew shadowJar
```

Запустить собранный JAR:

```bash
java -jar build/libs/paser-rubli-otzivi.jar
```

При старте бот:

1. Загружает конфигурацию.
2. Загружает cookies и прокси.
3. Получает категории акции Wildberries.
4. Загружает списки `Food.txt` и `detyam.txt`.
5. Регистрируется в Telegram.
6. Через 2 секунды автоматически запускает парсинг.

## Полезные системные свойства

Параметры можно передавать через `-D...`:

```bash
java \
  -Dwb.parser.targetRps=20 \
  -Dwb.parser.executorThreads=40 \
  -Dwb.parser.discoveryThreads=8 \
  -Dwb.parser.pageThreads=24 \
  -Dwb.parser.maxCycleMs=300000 \
  -Dwb.parser.maxDiscoveryMs=120000 \
  -Dwb.http.maxRetries=2 \
  -jar build/libs/paser-rubli-otzivi.jar
```

Основные свойства:

- `wb.parser.targetRps` - целевой RPS одной половины парсера, по умолчанию `20`.
- `wb.parser.executorThreads` - общий пул обработки страниц, по умолчанию `40`.
- `wb.parser.discoveryThreads` - потоки discovery-фазы, по умолчанию `8`.
- `wb.parser.pageThreads` - потоки обработки страниц, по умолчанию `24`.
- `wb.parser.maxCycleMs` - максимальная длительность цикла, по умолчанию `300000`.
- `wb.parser.maxDiscoveryMs` - лимит discovery-фазы, по умолчанию `120000`.
- `wb.http.maxRetries` - retry для WB-запросов с прокси, по умолчанию `2`.
- `wb.userAgent`, `wb.spaVersion`, `wb.deviceId` - HTTP-заголовки для запросов к Wildberries.
- `wb.proxyHealthCheck` - health-check прокси, по умолчанию `true`.
- `wb.proxyHealthTimeoutMs` - timeout проверки прокси, по умолчанию `2000`.

## Telegram-команды

Команды доступны только chat id из списка администраторов в `MyDualBot`:

- `/run` - запустить парсинг.
- `/stop` - остановить парсинг.
- `/clear` - перечитать/почистить кэши отправленных артикулов и перезапустить парсинг.
- `/stopFree` - остановить отправку в бесплатный чат.
- `/runFree` - включить отправку в бесплатный чат.
- `/pidory` - добавить продавца в блок-лист.
- `/reboot` - выполнить перезагрузку сервера через `rebootServer`.
- `/help` - показать список команд.

Рабочие chat id и thread id сейчас зашиты в `MyDualBot.java`, в вызовах `runSender(...)`.

## Тесты

Запустить все тесты:

```bash
./gradlew test
```

Запустить прямой интеграционный тест парсинга:

```bash
./gradlew runProductParsingTest
```

Live smoke-тест Wildberries выключен по умолчанию. Для запуска нужны актуальные `cookies.txt` и `proxies.txt`:

```bash
./gradlew test -Dwb.live.tests=true
```

## Логи и состояние

- Основной лог пишется в `parser.log`.
- Logback хранит rolling-логи по шаблону `parser.yyyy-MM-dd.log`, глубина хранения - 14 дней.
- Состояние уже отправленных товаров пишется в `sent_articles*.txt`.
- Эти файлы влияют на дедупликацию: удаление или очистка приведет к повторной отправке найденных артикулов.
