# Сборка APK в Docker-контейнере

Если у вас нет Android Studio или Android SDK, можно собрать APK внутри Docker-контейнера.

## Требования

- Установленный [Docker](https://docs.docker.com/get-docker/)
- ~5 ГБ свободного места (Android SDK)

## Шаги

### 1. Соберите Docker-образ

Из корня проекта:

```bash
docker build -t android-builder .
```

Первая сборка займёт несколько минут — скачивается JDK и Android SDK.

### 2. Соберите APK

```bash
docker run --rm -v "$(pwd)":/project android-builder ./gradlew assembleDebug
```

APK появится в:

```
app/build/outputs/apk/debug/app-debug.apk
```

### 3. Соберите release-версию (опционально)

```bash
docker run --rm -v "$(pwd)":/project android-builder ./gradlew assembleRelease
```

Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`

> **Примечание:** Release-версия будет без подписи. Для установки на устройство без ADB
> потребуется подписать APK. Для установки через `adb install` unsigned-версия подойдёт.

### 4. Установите на приставку

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Всё одной командой

```bash
docker build -t android-builder . && \
docker run --rm -v "$(pwd)":/project android-builder ./gradlew assembleDebug && \
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Возможные проблемы

| Проблема | Решение |
|----------|---------|
| `Permission denied` при запуске gradlew | Выполните `chmod +x gradlew` перед сборкой |
| Не хватает памяти | Увеличьте лимит Docker: Settings → Resources → Memory (минимум 4 ГБ) |
| Долгая первая сборка | Нормально — Gradle скачивает зависимости. Повторные сборки быстрее благодаря volume |

## Кэширование Gradle (ускорение повторных сборок)

Чтобы не скачивать зависимости каждый раз, добавьте volume для Gradle-кэша:

```bash
docker run --rm \
  -v "$(pwd)":/project \
  -v gradle-cache:/root/.gradle \
  android-builder ./gradlew assembleDebug
```

Именованный volume `gradle-cache` сохранится между запусками контейнера.
