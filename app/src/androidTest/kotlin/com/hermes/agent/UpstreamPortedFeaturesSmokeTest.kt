package com.hermes.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.agent.data.device.PrivilegedShellGateway
import com.hermes.agent.data.device.PrivilegedShellRetryGate
import com.hermes.agent.data.llm.CloudProviderRegistry
import com.hermes.agent.data.llm.LocalModelPreflight
import com.hermes.agent.data.llm.LocalModelValidator
import com.hermes.agent.data.llm.ModelCatalog
import com.hermes.agent.data.llm.ModelValidation
import com.hermes.agent.data.llm.PreflightLevel
import com.hermes.agent.data.oauth.OAuthManager
import com.hermes.agent.domain.device.PrivilegedShellBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device instrumentation smoke test verifying upstream ported features (Phases 1-4)
 * on physical hardware or emulator.
 */
@RunWith(AndroidJUnit4::class)
class UpstreamPortedFeaturesSmokeTest {

    @Test
    fun testGgufValidationOnDeviceFileSystem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tempFile = File(context.cacheDir, "smoke_test_model.gguf")
        try {
            // Write a small valid GGUF structure header
            FileOutputStream(tempFile).use { fos ->
                val buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
                buf.putInt(0x46554747) // "GGUF" magic
                buf.putInt(3)          // version 3
                buf.putLong(0)         // tensor count = 0
                buf.putLong(0)         // metadata count = 0
                fos.write(buf.array(), 0, 24)
            }

            runBlocking {
                val result = LocalModelValidator.validate(tempFile)
                // Lacks architecture & chat template, so should be Rejected cleanly
                assertTrue(result is ModelValidation.Rejected)
                val reason = (result as ModelValidation.Rejected).reason
                assertTrue(reason.isNotBlank())
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testPhysicalRamPreflightEvaluation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val totalRamBytes = memoryInfo.totalMem
        val availRamBytes = memoryInfo.availMem
        val modelBytes = 1_000_000_000L // ~1GB model

        val decision = LocalModelPreflight.evaluate(
            modelBytes = modelBytes,
            totalRamBytes = totalRamBytes,
            availableRamBytes = availRamBytes,
            lowMemory = memoryInfo.lowMemory,
        )

        assertNotNull(decision)
        if (totalRamBytes >= 4L * 1024 * 1024 * 1024 && !memoryInfo.lowMemory) {
            assertTrue(decision.allowed)
            assertTrue(decision.level == PreflightLevel.OPTIMAL || decision.level == PreflightLevel.WARNING)
        }
    }

    @Test
    fun testPinnedModelCatalogIntegrity() {
        val models = ModelCatalog.MODELS
        assertTrue(models.isNotEmpty())
        for (model in models) {
            assertTrue(model.revision.isNotBlank())
            assertEquals(64, model.sha256.length) // 64 hex characters for SHA-256
            assertTrue(model.url.contains("/${model.revision}/"))
        }
    }

    @Test
    fun testShizukuGatewayFallbackAndRetryGate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val retryGate = PrivilegedShellRetryGate()
        val gateway = PrivilegedShellGateway(context, retryGate)

        runBlocking {
            val status = gateway.getStatus()
            assertNotNull(status)
            // Even if Shizuku app is not running in CI / test device, getStatus() must return safely without crashing
            assertTrue(
                status.status == PrivilegedShellBackend.Status.NOT_INSTALLED ||
                    status.status == PrivilegedShellBackend.Status.DEAD ||
                    status.status == PrivilegedShellBackend.Status.PERMISSION_REQUIRED ||
                    status.status == PrivilegedShellBackend.Status.READY,
            )

            assertTrue(retryGate.canExecute())
            retryGate.onExecutionFailure(unverifiedUnwind = true, reason = "Smoke test unwind")
            assertFalse(retryGate.canExecute())
            retryGate.resetGate()
            assertTrue(retryGate.canExecute())
        }
    }

    @Test
    fun testOAuthManagerPkceUrlGeneration() {
        val manager = OAuthManager()
        val (openRouterUrl, openRouterSession) = manager.buildAuthorizationUrl("openrouter")
        assertTrue(openRouterUrl.startsWith("https://openrouter.ai/auth?"))
        assertEquals("openrouter", openRouterSession.providerId)
        assertTrue(openRouterSession.codeVerifier.isNotBlank())

        val (nousUrl, nousSession) = manager.buildAuthorizationUrl("nous")
        assertTrue(nousUrl.startsWith("https://portal.nousresearch.com/oauth/authorize?"))
        assertEquals("nous", nousSession.providerId)
        assertTrue(nousSession.state.isNotBlank())

        val nousDef = CloudProviderRegistry.definition("nous")
        assertNotNull(nousDef)
        assertEquals("Nous Portal", nousDef?.name)
    }
}
