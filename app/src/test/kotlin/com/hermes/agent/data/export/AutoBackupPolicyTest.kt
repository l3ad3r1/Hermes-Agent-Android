package com.hermes.agent.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupPolicyTest {

    private fun name(day: Int) = "hermes-auto-backup-2026-09-%02d-0300.hbk".format(day)

    @Test
    fun `keeps the newest N and deletes the older ones`() {
        val names = (1..7).map(::name)
        val doomed = AutoBackupPolicy.toDelete(names, keep = 5)
        assertEquals(listOf(name(2), name(1)), doomed)
    }

    @Test
    fun `never touches files it did not name`() {
        val names = listOf(name(1), name(2), "notes.txt", "hermes-full-backup-2026-09-20.hbk", "hermes-auto-backup-x.zip")
        assertEquals(listOf(name(1)), AutoBackupPolicy.toDelete(names, keep = 1))
    }

    @Test
    fun `keep is never below one so the newest backup always survives`() {
        val names = listOf(name(1), name(2))
        assertEquals(listOf(name(1)), AutoBackupPolicy.toDelete(names, keep = 0))
        assertTrue(AutoBackupPolicy.toDelete(names, keep = 10).isEmpty())
    }

    @Test
    fun `file names carry the date and sort by it`() {
        val a = AutoBackupPolicy.fileName(1_700_000_000_000L)
        val b = AutoBackupPolicy.fileName(1_700_100_000_000L)
        assertTrue(a.startsWith("hermes-auto-backup-2023-11-"))
        assertTrue(a < b)
        assertTrue(a.endsWith(".hbk"))
    }
}
