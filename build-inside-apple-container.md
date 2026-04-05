# Сборка APK в Apple Container (macOS)

Apple Container — инструмент от Apple для запуска OCI-совместимых Linux-контейнеров на macOS (Apple Silicon) через Virtualization.framework. Работает без Docker Desktop.

## Требования

- Mac с Apple Silicon (M1/M2/M3/M4)
- macOS 15 (Sequoia) или новее
- Xcode Command Line Tools
- Swift 6.1+

## Установка Apple Container

### Шаг 1. Установите Swift и Xcode CLI

```bash
xcode-select --install
```

### Шаг 2. Установите container tool

```bash
git clone https://github.com/apple/container
cd container
swift build -c release
sudo cp .build/release/container /usr/local/bin/
```

Проверьте установку:

```bash
container --help
```

## Сборка APK

### Шаг 3. Соберите Docker-образ

Apple Container поддерживает OCI-образы и может использовать существующий `Dockerfile` из проекта.

Из корня проекта:

```bash
container build --tag android-builder .
```

> **Примечание:** Apple Container использует тот же формат Dockerfile, что и Docker.
> Образ `eclipse-temurin:17-jdk-jammy` — это Linux (arm64), который отлично работает на Apple Silicon.

### Шаг 4. Запустите сборку

```bash
container run --rm --memory 4g \
  --mount "type=bind,source=$(pwd),target=/project" \
  android-builder \
  ./gradlew assembleDebug
```

> **Важно:** Флаг `--memory 4g` выделяет контейнеру 4 ГБ RAM. Если сборка падает
> с ошибкой «daemon disappeared», увеличьте до `--memory 6g`.

APK появится в:

```
app/build/outputs/apk/debug/app-debug.apk
```

### Шаг 5. Release-версия (опционально)

```bash
container run --rm \
  --mount "type=bind,source=$(pwd),target=/project" \
  android-builder \
  ./gradlew assembleRelease
```

## Всё одной командой

```bash
container build --tag android-builder . && \
container run --rm \
  --mount "type=bind,source=$(pwd),target=/project" \
  android-builder \
  ./gradlew assembleDebug
```

## Кэширование Gradle

Для ускорения повторных сборок создайте именованный volume:

```bash
container run --rm \
  --mount "type=bind,source=$(pwd),target=/project" \
  --mount "type=volume,source=gradle-cache,target=/root/.gradle" \
  android-builder \
  ./gradlew assembleDebug
```

## Установка APK на приставку

```bash
# Установите ADB
brew install android-platform-tools

# Подключитесь к приставке по Wi-Fi
adb connect IP_ПРИСТАВКИ:5555

# Установите APK
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Альтернатива: Docker Desktop на macOS

Если Apple Container не подходит, можно использовать Docker Desktop:

```bash
brew install --cask docker
# Запустите Docker Desktop, затем:
docker build -t android-builder .
docker run --rm -v "$(pwd)":/project android-builder ./gradlew assembleDebug
```

## Возможные проблемы

| Проблема | Решение |
|----------|---------|
| `container: command not found` | Убедитесь, что бинарник скопирован в `/usr/local/bin/` |
| Ошибка Virtualization.framework | Требуется macOS 15+ и Apple Silicon |
| `Permission denied` для gradlew | `chmod +x gradlew` перед сборкой |
| Медленная первая сборка | Нормально — скачиваются Gradle и Android SDK. Используйте volume для кэша |
| Архитектура arm64 vs x86_64 | На Apple Silicon образ собирается под arm64 — это корректно для Android SDK |
