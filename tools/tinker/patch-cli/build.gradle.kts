/*
 * Command-line front end for Tencent's tinker-patch-lib (the library behind tinker-patch-cli.jar,
 * which Tencent does not publish to Maven Central). Same version as the app's Tinker runtime.
 * See docs/TINKER-HOTFIX.md.
 */
plugins {
    application
}

val tinkerVersion = "1.9.15.2" // keep equal to `tinker` in gradle/libs.versions.toml

dependencies {
    implementation("com.tencent.tinker:tinker-patch-lib:$tinkerVersion")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

application {
    mainClass.set("com.hermes.tools.tinker.HermesTinkerPatchCli")
    applicationName = "hermes-tinker-patch-cli"
}
