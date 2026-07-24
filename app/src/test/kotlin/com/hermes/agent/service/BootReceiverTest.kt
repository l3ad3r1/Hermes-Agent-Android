package com.hermes.agent.service

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {

    @Test
    fun `credential protected boot schedules reconciliation`() {
        assertTrue(shouldScheduleBootReconciliation(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun `locked boot does not touch credential protected app storage`() {
        assertFalse(shouldScheduleBootReconciliation(Intent.ACTION_LOCKED_BOOT_COMPLETED))
    }

    @Test
    fun `unrelated broadcasts are ignored`() {
        assertFalse(shouldScheduleBootReconciliation(Intent.ACTION_TIME_CHANGED))
        assertFalse(shouldScheduleBootReconciliation(null))
    }
}
