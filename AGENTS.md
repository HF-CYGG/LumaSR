# Repository Guidelines

## Project Structure & Module Organization
LumaSR is an Android local AI image super-resolution app. It targets offline inference with ncnn and Vulkan/CPU fallback, supporting Waifu2x and RealCUGAN workflows with minimal controls, tile progress preview, and before/after comparison.

The repository is being initialized. Use this intended layout when adding code:
- `app/src/main/java/...` for Kotlin, Jetpack Compose screens, state holders, repositories, and use cases.
- `app/src/main/cpp/` for JNI bridges, CMake files, ncnn integration, and Vulkan/CPU inference code.
- `app/src/main/assets/` for `model_manifest.json` and bundled model packs.
- `app/src/test/` for JVM unit tests and `app/src/androidTest/` for emulator or device tests.

## Build, Test, and Development Commands
After the Gradle Android project is generated, use:
- `./gradlew assembleDebug` to build a debug APK.
- `./gradlew testDebugUnitTest` to run JVM unit tests.
- `./gradlew connectedDebugAndroidTest` to run instrumentation tests on a connected device or emulator.
- `./gradlew lintDebug` to run Android Lint checks.

On Windows PowerShell, use `.\gradlew` instead of `./gradlew`.

## Coding Style & Naming Conventions
Use Kotlin, Jetpack Compose, Material 3, MVVM, StateFlow, Repository, and UseCase patterns. Use PascalCase for classes and composables, camelCase for functions and properties, and lowercase package names. Keep native filenames and symbols grouped around `superres_*` or explicit JNI bridge names.

Add comments only where they explain intent, risk, or boundaries. Kotlin comments should cover complex state flows, task scheduling, image I/O, and error fallback paths. JNI/C++ comments are required around memory ownership, Vulkan/CPU fallback, tile strategy, and cancellation behavior. Avoid comments that merely repeat the code.

## Testing Guidelines
Cover model manifest parsing, preset-to-parameter mapping, task state transitions, cancellation, output validation, and error messages. Device tests should verify Photo Picker import, processing progress, comparison UI, save/share flow, and Vulkan-to-CPU fallback. Name tests by behavior, for example `loadsDefaultModelManifest`.

## Commit & Pull Request Guidelines
No Git history exists yet, so use concise imperative commit messages such as `Add model manifest loader`. Pull requests should include a short summary, test evidence, affected screens or native modules, and screenshots or recordings for UI changes.

## Security & Configuration Tips
Keep image processing local by default. Do not commit private photos, generated outputs, signing keys, tokens, or local SDK paths. Do not request broad storage permissions when Android Photo Picker or MediaStore scoped access is sufficient.
