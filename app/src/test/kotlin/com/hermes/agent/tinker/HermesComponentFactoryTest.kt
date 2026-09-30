package com.hermes.agent.tinker

import dagger.hilt.android.internal.modules.ApplicationContextModule
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * HermesComponentFactory builds the Hilt singleton component by name (Hilt generates it after the
 * app's sources compile). If Hilt ever renames it, this fails instead of the app at start-up.
 */
class HermesComponentFactoryTest {

    @Test
    fun `the generated Hilt root component and its builder methods exist`() {
        val component = Class.forName(HermesComponentFactory.COMPONENT, false, javaClass.classLoader)
        val builderMethod = component.getMethod("builder")
        val builderType = builderMethod.returnType
        assertNotNull(builderType.getMethod("applicationContextModule", ApplicationContextModule::class.java))
        assertNotNull(builderType.getMethod("build"))
    }
}
