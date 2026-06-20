/*
 * Hermes Agent — Android App
 * Root settings file. Phase 1 (Foundation) of the technical plan.
 *
 * Module layout:
 *   :app   — single Android application module (Kotlin + Jetpack Compose + Hilt)
 *
 * Multi-module split (data / domain / ui / inference) is deferred to Phase 2
 * per the development roadmap.
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
    }
}

rootProject.name = "Hermes Agent"
include(":app")
