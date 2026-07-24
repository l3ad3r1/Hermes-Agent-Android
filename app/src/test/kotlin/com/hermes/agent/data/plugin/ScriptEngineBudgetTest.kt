package com.hermes.agent.data.plugin

import com.l3ad3r1.octojotter.plugin.ScriptEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptEngineBudgetTest {

    @Test(timeout = 5_000L)
    fun `infinite community plugin is terminated by the instruction budget`() = runBlocking {
        val engine = ScriptEngine()

        withTimeout(4_000L) {
            engine.reload(
                listOf(
                    ScriptEngine.PluginSpec(
                        id = "runaway",
                        source = "while (true) {}",
                        permissions = emptySet(),
                    ),
                ),
            )
        }

        assertTrue(engine.commands().isEmpty())
    }
}
