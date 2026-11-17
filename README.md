# vinlagonChat

Android-проект агента, package `chat.vinlagon.secret`. Это развиваемая база, собранная вокруг безопасного sandbox/allowlist-подхода. Интерфейс карточек вдохновлён переданными референсами.

## Что реализовано в исходниках

- Compose/M3: `CollapsibleStepCard`, `FileArtifactCard`, `ExecutionFooter`, чат с потоковой выдачей, редактор файлов, выбор API-провайдера, настройки и экран подтверждения действий.
- Streaming runtime на Ktor SSE: OpenAI-compatible API (включая OpenRouter endpoint) и Anthropic Messages; обрабатываются потоковый текст и tool-calls, есть лимиты истории и сетевые таймауты.
- Шифрованное хранилище API/GitHub секретов через Android Keystore + EncryptedSharedPreferences; история чата хранится в Room, SQLCipher passphrase защищён KeyStore-backed storage.
- Tools: `search_in_web`, `read_page`, `bash_used`, `read_file`, `write_file`, `run_app`, `run_task`, `build_runner`, `apply_theme`, `interpolation_script` (только безопасные арифметические выражения). Web/file/model tool calls проходят через `ToolOrchestrator` и `SecurityGate`.
- `SecurityGate` с TRUST/MEDIUM/STRICT/CUSTOM. В UI активен CUSTOM allowlist: web/read и sandbox `pwd/ls/cat` разрешены, запись файла требует явного подтверждения; запуск приложения/планирование требуют подтверждения. Опасные командные маркеры отклоняются независимо от уровня.
- Workspace: внутренняя sandbox с canonical-path guard, SAF-папка с persistable URI grant, и GitHub Contents API VFS для чтения/записи/tree listing. Лимит workspace-файла — 2 MiB.
- `run_task` ставит локальное напоминание через WorkManager; это не точный alarm и не меняет Wi-Fi. `run_app` запускает видимый launcher intent. `build_runner` пока честно возвращает «не настроен», не скачивает неизвестные бинарники и не запускает сборку внутри приложения.
- JSON theme parser и `apply_theme` меняют Material 3 схему в рантайме.
- Gradle включает Compose, Ktor, Room/SQLCipher, DataStore, WorkManager, Libsu, Shizuku, Chaquopy. JNI starter target имеет `-Wl,-z,max-page-size=16384`.
- Есть unit tests для reducer, sandbox path traversal/backup и строгого approval gate.

## Собрать APK

Требуются JDK 17, Android SDK Platform 35, NDK `27.2.12479018`, CMake 3.22.1, Gradle 8.9+ и Python 3.8+ на машине сборки (для Chaquopy), а также доступ к Google Maven, Maven Central, Rikka и JitPack:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Debug APK появится по адресу `app/build/outputs/apk/debug/app-debug.apk`. Workflow `.github/workflows/android.yml` проверяет тесты и прикладывает APK как GitHub Actions artifact после push. В этом окружении нет Java/JDK, Android SDK/NDK и Gradle executable, поэтому APK здесь не собран и тесты не запускались.

Release signing настроен на переменные окружения `VINLAGON_KEYSTORE`, `VINLAGON_STORE_PASSWORD`, `VINLAGON_KEY_ALIAS`, `VINLAGON_KEY_PASSWORD`. Без keystore Gradle не подписывает release-сборку. Для публикации используйте собственный ключ локально или CI secret store; не передавайте ключ и пароли в чат и не коммитьте их.

## Явные ограничения этой итерации

- `.gguf` пока не исполняется: в проекте есть JNI-заготовка, но нет llama.cpp, GPU backend или модельного loader. Пункт Local GGUF в селекторе показывает это ограничение.
- Libsu/Shizuku добавлены как зависимости, но привилегированные движки намеренно не запускают произвольные команды. `bash_used` — виртуальный интерпретатор только для трёх операций в sandbox, не POSIX shell.
- Python VM Chaquopy стартует; `interpolation_script` принимает только ограниченную арифметику. Произвольный Python script runner не открыт: исполнение недоверенного кода в процессе приложения не является безопасной песочницей.
- `build_runner` отключён до появления отдельно изолированного builder-сервиса. `run_task` не выполняет root/Shizuku системные действия.
- Compile/target SDK стоят на 35 как воспроизводимая стартовая цель. Это не сертификация Android API 36/37. Native/libs alignment и работа на API 35–37 должны быть подтверждены реальной сборкой и тестами на устройствах.
- Google/Anthropic/OpenRouter требуют действующих endpoint/model/API key. GitHub workspace требует fine-grained token с Contents read/write для нужного репозитория. Секреты остаются локальными.
