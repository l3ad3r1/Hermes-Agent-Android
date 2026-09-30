// Standalone build, deliberately NOT part of the Android build: it needs only Maven Central and a
// JDK, so it runs on the release PC and on CI without the Android SDK.
//   ./gradlew -p tools/tinker/patch-cli installDist
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "hermes-tinker-patch-cli"
