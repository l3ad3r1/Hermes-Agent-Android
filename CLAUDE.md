# CLAUDE.md

Follow `E:\claude-projects\CLAUDE.md` for shared working rules. This file contains only project facts.

## Project

Hermes Agent Android: Kotlin, Jetpack Compose, Hilt, and Room. Package `com.hermes.agent`; minSdk 29, target 36. Toolchain: Gradle 9.6.1 / AGP 9.1.1 / Kotlin 2.2.10 / KSP 2.3.5, JBR/JDK 21. AGP 9 has built-in Kotlin support — do NOT apply the `kotlin-android` plugin; configure Kotlin via the top-level `kotlin { }` block, not `android { kotlinOptions }`.

- Build environment: `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`; `ANDROID_HOME=C:\Users\renja\AppData\Local\Android\Sdk`.
- Compile: `./gradlew :app:compileDebugKotlin`.
- Unit tests: `./gradlew :app:testDebugUnitTest` using Robolectric.
- On-device inference is real: `app/src/main/cpp/llama.cpp` is a pinned git submodule built via CMake/NDK (arm64-v8a only); the JNI bridge lives under `com.arm.aichat`. Run `git submodule update --init` before a first build. `assembleDebug` compiles the native libs and yields an ~87 MB APK.
- `versionCode`/`versionName` and the OTA `updateRepo` live in `gradle.properties` (`hermes.*`), not hardcoded in the build file.
- Signed artifact: `./gradlew :app:assembleRelease`; signing uses gitignored `hermes.local.properties` and `hermes-release.jks` at the repository root. Never move or regenerate the keystore; signer SHA-256 starts `99255c31`.
- A new agent tool requires registration in `di/ToolsModule`, access in `data/agent/agents/AgentToolAccess`, and mention in persona prompts.
- `CloudLlmProvider` is OpenAI-compatible. Nous/Hermes models emit tool calls as text tags; retain the fallback parser.
- `SkillGuard` vets skill content. Rewrites preserve frontmatter; `SkillConstraints` enforces 15 KB and 1.5x growth limits.
