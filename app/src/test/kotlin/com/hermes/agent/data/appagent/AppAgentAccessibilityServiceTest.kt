package com.hermes.agent.data.appagent

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.Shadows.shadowOf
import com.hermes.agent.service.AppAgentAccessibilityService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppAgentAccessibilityServiceTest {

    @Test
    fun `dispatchTap creates correct gesture`() {
        val service = AppAgentAccessibilityService()
        val shadowService = shadowOf(service) as ShadowAccessibilityService

        val result = service.dispatchTap(100f, 200f)
        
        // In Robolectric, dispatchGesture returns true by default and stores the gesture
        assertTrue(result)
        
        val gestures = shadowService.gesturesDispatched
        assertEquals(1, gestures.size)
        
        val gesture = gestures[0]
        assertNotNull(gesture)
    }

    @Test
    fun `dispatchSwipe creates correct gesture`() {
        val service = AppAgentAccessibilityService()
        val shadowService = shadowOf(service) as ShadowAccessibilityService

        val result = service.dispatchSwipe(10f, 10f, 50f, 50f, 300L)
        
        assertTrue(result)
        
        val gestures = shadowService.gesturesDispatched
        assertEquals(1, gestures.size)
    }
}
