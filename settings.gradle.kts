/*
 * Hermes Agent — Android App
 * Root settings file.
 *
 * Module layout:
 *   :app   — single Android application module (Kotlin + Jetpack Compose + Hilt)
 *
 * Core engine modules live in the agent-core repo. Their source directories
 * are mapped here via projectDir overrides so that project(":core:*")
 * references in :app/build.gradle.kts resolve seamlessly.
 *
 * When agent-core is published to GitHub Packages, these include + projectDir
 * overrides will be replaced by module coordinates and an includeBuild for
 * local development.
 */

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Termux terminal engine (terminal-view / terminal-emulator) is
        // published from github.com/termux/termux-app via JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Hermes Agent"
include(":app")

// Shared engine modules — source lives in ../agent-core, mapped by projectDir.
include(":core:util")
include(":core:domain")
include(":core:theme")
include(":core:plugin")
include(":core:settings")
include(":core:persistence")
include(":core:memory")
include(":core:llm")
include(":core:tools")

project(":core:util").projectDir = file("../agent-core/core/util")
project(":core:domain").projectDir = file("../agent-core/core/domain")
project(":core:theme").projectDir = file("../agent-core/core/theme")
project(":core:plugin").projectDir = file("../agent-core/core/plugin")
project(":core:settings").projectDir = file("../agent-core/core/settings")
project(":core:persistence").projectDir = file("../agent-core/core/persistence")
project(":core:memory").projectDir = file("../agent-core/core/memory")
project(":core:llm").projectDir = file("../agent-core/core/llm")
project(":core:tools").projectDir = file("../agent-core/core/tools")
