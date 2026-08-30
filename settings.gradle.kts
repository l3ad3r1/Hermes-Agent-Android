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
 * agent-core is NOT vendored into this repo — it must be checked out
 * separately. See agentCoreDir below for where it is looked for; a checkout
 * that cannot find it fails here with instructions rather than with Gradle's
 * bare "projectDirectory does not exist".
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

// Shared engine modules — source lives in the agent-core repo, mapped by projectDir.
//
// Resolution order, first hit wins:
//   1. -PagentCoreDir=<path> or the AGENT_CORE_DIR environment variable
//   2. ./agent-core        — a checkout inside this repo (what CI does)
//   3. ../agent-core       — a sibling checkout (the local dev layout)
val agentCoreDir: File = run {
    val explicit = (settings.providers.gradleProperty("agentCoreDir").orNull
        ?: System.getenv("AGENT_CORE_DIR"))
        ?.takeIf { it.isNotBlank() }
        ?.let { file(it) }
    // An explicitly named path is authoritative: honour it or fail. Only the
    // implicit layouts fall through to each other.
    val candidates = if (explicit != null) listOf(explicit) else listOf(file("agent-core"), file("../agent-core"))
    candidates.firstOrNull { File(it, "core/domain/build.gradle.kts").isFile }
        ?: error(
            buildString {
                appendLine("Cannot find the agent-core engine checkout.")
                appendLine()
                appendLine("This app maps its :core:* Gradle projects into the separate")
                appendLine("agent-core repository. Clone it beside this checkout:")
                appendLine()
                appendLine("    git clone https://github.com/l3ad3r1/agent-core.git ../agent-core")
                appendLine()
                appendLine("...or point at an existing clone with -PagentCoreDir=<path>")
                appendLine("or the AGENT_CORE_DIR environment variable.")
                appendLine()
                appendLine("Looked in:")
                candidates.forEach { appendLine("  - $it") }
            }
        )
}

include(":core:util")
include(":core:domain")
include(":core:theme")
include(":core:plugin")
include(":core:settings")
include(":core:persistence")
include(":core:memory")
include(":core:llm")
include(":core:tools")

listOf("util", "domain", "theme", "plugin", "settings", "persistence", "memory", "llm", "tools")
    .forEach { module -> project(":core:$module").projectDir = File(agentCoreDir, "core/$module") }
