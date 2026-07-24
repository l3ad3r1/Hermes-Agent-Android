package com.hermes.agent.data.backup

import com.l3ad3r1.octojotter.data.local.NoteEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupDataTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `locked and encrypted notes never enter a Gist backup`() {
        assertNull(NoteEntity(title = "Locked", content = "secret", locked = true).toBackupOrNull())
        assertNull(NoteEntity(title = "Encrypted", content = "cipher", encrypted = true).toBackupOrNull())
    }

    @Test
    fun `note backup preserves trash repository and sync metadata`() {
        val note = NoteEntity(
            title = "Repo note",
            content = "body",
            repository = "owner/repo",
            path = "notes/item.md",
            sha = "abc123",
            deletedAt = 1234L,
            pendingRemoteDelete = true,
            remoteUpdatedAt = "2026-07-14T00:00:00Z",
            lastSyncedContentHash = "hash",
            conflictState = "CONFLICT",
            conflictedRemoteContent = "remote body",
            conflictedRemoteModifiedAt = 5678L,
            needsSync = true,
            lastModifiedLocally = 9999L,
        )

        val restored = checkNotNull(note.toBackupOrNull()).toRestoredEntity()

        assertEquals(note.title, restored.title)
        assertEquals(note.content, restored.content)
        assertEquals(note.repository, restored.repository)
        assertEquals(note.path, restored.path)
        assertEquals(note.sha, restored.sha)
        assertEquals(note.deletedAt, restored.deletedAt)
        assertEquals(note.pendingRemoteDelete, restored.pendingRemoteDelete)
        assertEquals(note.remoteUpdatedAt, restored.remoteUpdatedAt)
        assertEquals(note.lastSyncedContentHash, restored.lastSyncedContentHash)
        assertEquals(note.conflictState, restored.conflictState)
        assertEquals(note.conflictedRemoteContent, restored.conflictedRemoteContent)
        assertEquals(note.conflictedRemoteModifiedAt, restored.conflictedRemoteModifiedAt)
        assertEquals(note.needsSync, restored.needsSync)
        assertEquals(note.lastModifiedLocally, restored.lastModifiedLocally)
    }

    @Test
    fun `schema five serializes credentials as blank by default`() {
        val encoded = json.encodeToString(BackupData(settings = SettingsBackup(cloudEnabled = true)))
        val decoded = json.decodeFromString<BackupData>(encoded)

        assertEquals(5, decoded.schemaVersion)
        assertTrue(decoded.settings!!.cloudEnabled)
        assertTrue(decoded.settings!!.cloudApiKey.isBlank())
        assertTrue(decoded.settings!!.auxApiKey.isBlank())
    }

    @Test
    fun `legacy schema four note receives safe defaults`() {
        val decoded = json.decodeFromString<BackupData>(
            """{"schemaVersion":4,"notes":[{"title":"Old","content":"body"}]}""",
        )

        assertEquals(4, decoded.schemaVersion)
        val note = decoded.notes.single()
        assertNull(note.repository)
        assertNull(note.deletedAt)
        assertFalse(note.encrypted)
        assertFalse(note.needsSync)
    }
}
