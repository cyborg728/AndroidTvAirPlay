# Сборка APK в Apple Container (macOS)

Apple Container — инструмент от Apple для запуска OCI-совместимых Linux-контейнеров на macOS (Apple Silicon) через Virtualization.framework. Работает без Docker Desktop.

> **Важно:** Android SDK содержит бинарники AAPT2 только для x86_64 Linux.
> На Apple Silicon необходимо запускать контейнер в режиме x86_64 через Rosetta.

## Требования

- Mac с Apple Silicon (M1/M2/M3/M4)
- macOS 15 (Sequoia) или новее
- Xcode Command Line Tools
- Swift 6.1+
- **Rosetta** (для запуска x86_64 контейнеров)

## Установка

### Шаг 1. Установите Rosetta

```bash
softwareupdate --install-rosetta --agree-to-license
```

### Шаг 2. Установите Swift и Xcode CLI

```bash
xcode-select --install
```

### Шаг 3. Установите container tool

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

### Шаг 4. Соберите Docker-образ (x86_64)

Из корня проекта:

```bash
container build --platform linux/amd64 --tag android-builder .
```

Образ собирается под x86_64 — это **обязательно**, т.к. AAPT2 (часть Android build tools)
доступен только как x86_64 бинарник для Linux. На arm64 он не запустится.

### Шаг 5. Запустите сборку

```bash
container run --rm --memory 6g --platform linux/amd64 \
  --mount "type=bind,source=$(pwd),target=/project" \
  android-builder \
  ./gradlew assembleDebug
```

> **Важно:** Выделите контейнеру минимум 6 ГБ RAM (`--memory 6g`).
> Сборка под Rosetta (x86_64 эмуляция) потребляет больше памяти.

APK появится в:

```
app/build/outputs/apk/debug/app-debug.apk
```

### Шаг 6. Release-версия (опционально)

```bash
container run --rm --memory 6g --platform linux/amd64 \
  --mount "type=bind,source=$(pwd),target=/project" \
  android-builder \
  ./gradlew assembleRelease
```

## Всё одной командой

```bash
container build --platform linux/amd64 --tag android-builder . && \
container run --rm --memory 6g --platform linux/amd64 \
  --mount "type=bind,source=$(pwd),target=/project" \
  android-builder \
  ./gradlew assembleDebug
```

## Кэширование Gradle (ускорение повторных сборок)

Чтобы не скачивать зависимости каждый раз, добавьте volume для Gradle-кэша:

```bash
container run --rm --memory 6g --platform linux/amd64 \
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
docker build --platform linux/amd64 -t android-builder .
docker run --rm --platform linux/amd64 -m 6g -v "$(pwd)":/project android-builder ./gradlew assembleDebug
```

## Возможные проблемы

| Проблема | Решение |
|----------|---------|
| AAPT2: `Syntax error: Unterminated quoted string` | Контейнер запущен под arm64. Добавьте `--platform linux/amd64` |
| `Gradle daemon disappeared` | Увеличьте память: `--memory 8g` |
| `container: command not found` | Убедитесь, что бинарник скопирован в `/usr/local/bin/` |
| Ошибка Virtualization.framework | Требуется macOS 15+ и Apple Silicon |
| `Permission denied` для gradlew | `chmod +x gradlew` перед сборкой |
| Rosetta не установлена | `softwareupdate --install-rosetta --agree-to-license` |
| Медленная сборка | Нормально для x86_64 эмуляции. Используйте volume для Gradle-кэша |
