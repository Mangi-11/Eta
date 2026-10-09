# Repository Guidelines

## Project Structure & Module Organization

Eta is a single-module Android assistant built with Kotlin and Jetpack Compose.

- `app/src/main/kotlin/io/github/mangi/eta/`: `agent/` contains runtime and tools, `ui/` Compose screens, `data/` persistence, and `hook/` OEM/Xposed integrations.
- `app/src/test/kotlin/`: JVM tests mirroring production packages.
- `app/src/main/res/` and `app/src/main/assets/`: localized resources, bundled skills, catalogs, and licenses.
- `app/src/main/cpp/` and `app/src/main/jniLibs/`: terminal helper source and packaged native binaries.
- `gradle/libs.versions.toml` centralizes dependencies; `docs/` explains architecture and `scripts/` maintains native components and catalogs.

## Build, Test, and Development Commands

Use JDK 25 and Android SDK 37 (`platforms;android-37.0`); configure `sdk.dir` in ignored `local.properties`. Run commands from the repository root:

- `./gradlew :app:assembleDebug`: build `app/build/outputs/apk/debug/Eta-v<version>-debug.apk`.
- `./gradlew :app:installDebug`: install on a connected Android 13+ device or emulator; open Eta from its launcher icon.
- `./gradlew :app:testDebugUnitTest`: run JVM tests.
- `./gradlew :app:lintDebug`: run Android Lint, which fails on errors.
- `./gradlew :app:assembleRelease`: build with R8 optimization; configure signing for an installable release.

For native rebuilds, follow `docs/TERMINAL_NATIVE.md` and use the NDK version in the version catalog.

## Coding Style & Naming Conventions

Follow official Kotlin style with four-space indentation. Use PascalCase for types and Compose functions, camelCase for other functions/properties, UPPER_SNAKE_CASE for constants, and snake_case for Android resources. Use Android Studio formatting; no standalone formatter is configured. Keep English, Simplified Chinese, and Traditional Chinese resources synchronized.

## Testing Guidelines

Use JUnit 4 and Robolectric for Android-dependent tests. Name classes `<Subject>Test` and methods with descriptive camelCase behavior names. Add regression tests for changed behavior, including persistence migrations and permission boundaries. No numeric coverage threshold is configured. Run one class with `./gradlew :app:testDebugUnitTest --tests '*AppearanceSettingsTest'`. Validate OEM hooks on the relevant ROM/device.

## Commit & Pull Request Guidelines

Follow the history's `type(scope): summary` convention, such as `fix(voice): smooth playback transitions` or `chore(deps): update dependencies`; Chinese summaries are common. Keep commits focused. PRs should describe behavior changes, link related issues, list validation, and include screenshots for UI changes. Record Android/ROM and root/LSPosed setup for integration changes.

## Security & Configuration

Keep API keys, keystores, passwords, and local SDK paths out of Git. Release signing uses `ETA_RELEASE_*` environment variables; see `.github/RELEASING.md`. Use `AgentLogger`/`ModuleLogger` and exclude sensitive payloads from logs.
