# Инструкция по сборке проекта

## Быстрая сборка

### 1. Сборка JAR файла со всеми зависимостями (fat JAR)

```bash
# На Windows
gradlew.bat build

# На Linux/Mac
./gradlew build
```

JAR файл будет создан в: `build/libs/paser-rubli-otzivi.jar`

Этот JAR содержит все зависимости и готов к запуску на сервере.

## Что нужно для сборки

1. **Java 17+** (рекомендуется Java 21+)
   - Проверить: `java -version`

2. **Gradle** (опционально, используется wrapper)
   - Проект использует Gradle Wrapper (`gradlew` или `gradlew.bat`)
   - Gradle скачается автоматически при первом запуске

## Структура после сборки

```
build/
└── libs/
    └── paser-rubli-otzivi.jar  ← Готовый к развертыванию JAR
```

## Проверка сборки

После сборки проверьте размер JAR файла - он должен быть достаточно большим (обычно 50-100 МБ), так как содержит все зависимости.

## Что включено в JAR

- Все классы приложения
- Все зависимости (Telegram API, SQLite, Selenium, Gson и т.д.)
- Файлы из `src/main/resources` (включая `app.properties`)

## Важно

- `app.properties` из `src/main/resources` будет внутри JAR
- Для изменения конфигурации на сервере можно:
  1. Создать `app.properties` рядом с JAR файлом (приоритет)
  2. Или изменить `app.properties` в `src/main/resources` и пересобрать

