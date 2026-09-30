package com.hermes.agent.tinker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the hot-fix wiring that no unit test can exercise on a JVM: the manifest shell, the
 * loader-class rules tinker-patch-lib enforces when a patch is built, and the Gradle property
 * gating of patch builds. Like OpenClawWiringTest these read the source tree; CI's Tinker smoke
 * step then builds a real (unsigned) patch from two debug APKs.
 */
class TinkerWiringTest {

    private val app = File(".")
    private val repo = File("..")
    private fun read(path: String) = File(app, path).readText()

    @Test
    fun `the manifest application is the Tinker shell and TINKER_ID is declared`() {
        val manifest = read("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:name=\".tinker.loader.HermesTinkerApplication\""))
        assertTrue(manifest.contains("android:name=\"TINKER_ID\""))
        assertTrue(manifest.contains("android:value=\"\${tinkerId}\""))
        assertTrue("the result service must never be Tinker's killing default", manifest.contains(".tinker.HermesPatchResultService"))
    }

    @Test
    fun `the shell names the delegate that exists and refers only to loader classes`() {
        val shellDir = File(app, "src/main/java/com/hermes/agent/tinker/loader")
        val files = shellDir.listFiles { f -> f.extension == "java" || f.extension == "kt" }!!.toList()
        assertTrue(files.isNotEmpty())
        files.forEach { assertEquals("loader classes must be Java (no Kotlin runtime references): ${it.name}", "java", it.extension) }

        val shell = File(shellDir, "HermesTinkerApplication.java").readText()
        val delegate = Regex("DELEGATE = \"([^\"]+)\"").find(shell)!!.groupValues[1]
        assertEquals(HermesApplicationLike::class.java.name, delegate)

        // Mirrors tinker-patch-lib's "loader classes may only refer to loader classes" check.
        val allowed = listOf("android.", "java.", "dalvik.", "com.tencent.tinker.loader.", "com.hermes.agent.tinker.loader.")
        val allowedExact = setOf("dagger.hilt.internal.GeneratedComponentManager")
        for (file in files) {
            Regex("^import\\s+(?:static\\s+)?([\\w.]+);", RegexOption.MULTILINE).findAll(file.readText()).forEach { m ->
                val imported = m.groupValues[1]
                assertTrue(
                    "${file.name} imports $imported, which is not a loader class",
                    allowed.any { imported.startsWith(it) } || imported in allowedExact,
                )
            }
        }
    }

    @Test
    fun `tinker_config, proguard and the shell agree on the loader classes`() {
        val config = File(repo, "tools/tinker/tinker_config.xml").readText()
        val proguard = read("proguard-rules.pro")
        for (pattern in listOf("com.tencent.tinker.loader.*", "com.hermes.agent.tinker.loader.*", "dagger.hilt.internal.GeneratedComponentManager")) {
            assertTrue("tinker_config.xml must list loader $pattern", config.contains("<loader value=\"$pattern\"/>"))
        }
        assertTrue(proguard.contains("-keep class com.hermes.agent.tinker.loader.** { *; }"))
        assertTrue(proguard.contains("-keep class com.tencent.tinker.loader.** { *; }"))
        assertTrue(proguard.contains("dagger.hilt.internal.GeneratedComponentManager"))
        assertTrue(proguard.contains("DaggerHermesApp_HiltComponents_SingletonC"))
        assertTrue("patches are signed by the scripts with SHA-256, not by Tinker's SHA1 signer", config.contains("<useSign value=\"false\"/>"))
        assertTrue(config.contains("<ignoreWarning value=\"false\"/>"))
        // PatchGate binds the (unsigned) manifest's patchVersion to this signed package_meta field.
        assertTrue(config.contains("<configField name=\"${com.hermes.agent.data.hotfix.PatchGate.PATCH_VERSION_KEY}\" value=\"@PATCH_VERSION@\"/>"))
    }

    @Test
    fun `patch build mode is gated by gradle properties`() {
        val gradle = read("build.gradle.kts")
        assertTrue(gradle.contains("findProperty(\"hermes.tinker.base\")"))
        assertTrue(gradle.contains("findProperty(\"hermes.tinkerId\")"))
        // Stable ids and applymapping only come from an archived base; ids are always emitted.
        assertTrue(Regex("tinkerBaseDir\\?\\.let \\{.{0,400}--stable-ids", RegexOption.DOT_MATCHES_ALL).containsMatchIn(gradle))
        assertTrue(Regex("tinkerBaseDir\\?\\.let \\{.{0,400}mapping\\.txt", RegexOption.DOT_MATCHES_ALL).containsMatchIn(gradle))
        assertTrue(gradle.contains("-applymapping"))
        assertTrue(gradle.contains("\"--emit-ids\""))
        // A patch build takes the base's version so the manifest check and OTA comparisons hold.
        assertTrue(gradle.contains("versionCode = base.getProperty(\"versionCode\").toInt()"))
        assertTrue(gradle.contains("manifestPlaceholders[\"tinkerId\"]"))
    }

    @Test
    fun `the Tinker gradle plugin is not used`() {
        val all = read("build.gradle.kts") + File(repo, "build.gradle.kts").readText() + File(repo, "gradle/libs.versions.toml").readText()
        assertTrue(!all.contains("tinker-patch-gradle-plugin") && !all.contains("com.tencent.tinker.patch"))
    }
}
