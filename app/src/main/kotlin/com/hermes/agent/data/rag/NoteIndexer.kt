package com.hermes.agent.data.rag

import com.hermes.agent.domain.rag.Document
import com.hermes.agent.domain.rag.RagPipeline
import com.l3ad3r1.octojotter.data.local.NoteEntity
import com.l3ad3r1.octojotter.data.repository.NoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges Octo Jotter notes into the agent's RAG memory.
 *
 * Observes [NoteRepository] and synchronizes notes to the [RagPipeline]
 * so that the agent has semantic access to the user's Second Brain.
 *
 * Documents are given an ID of "note_{id}" and their createdAt is set to
 * the note's lastModifiedLocally timestamp, allowing us to skip re-indexing
 * unmodified notes.
 */
@Singleton
class NoteIndexer @Inject constructor(
    private val noteRepository: NoteRepository,
    private val ragPipeline: RagPipeline,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            combine(
                noteRepository.allNotes,
                noteRepository.trashNotes,
                ragPipeline.observeDocuments()
            ) { activeNotes, trashNotes, indexedDocs ->
                Triple(activeNotes, trashNotes, indexedDocs)
            }.collect { (activeNotes, trashNotes, indexedDocs) ->
                sync(activeNotes, trashNotes, indexedDocs)
            }
        }
    }

    private suspend fun sync(
        activeNotes: List<NoteEntity>,
        trashNotes: List<NoteEntity>,
        indexedDocs: List<Document>
    ) {
        val indexedMap = indexedDocs.associateBy { it.id }

        // Locked/encrypted notes are the user's explicitly-private notes
        // (biometric app-lock). They must never enter the vector store:
        // indexed content is injected into system prompts and sent to the
        // configured cloud LLM. Excluding them here also EVICTS a note the
        // moment the user locks it (it drops out of eligibleIds below).
        val eligibleNotes = activeNotes.filter { !it.locked && !it.encrypted }

        // 1. Evict any indexed note that is no longer eligible — trashed,
        // permanently deleted, or newly locked/encrypted.
        val eligibleIds = eligibleNotes.map { "note_${it.id}" }.toSet()

        for (doc in indexedDocs) {
            if (doc.id.startsWith("note_") && doc.id !in eligibleIds) {
                Timber.tag("NoteIndexer").i("Evicting note from RAG: %s", doc.id)
                ragPipeline.deleteDocument(doc.id)
            }
        }

        // 2. Ingest new or modified notes
        for (note in eligibleNotes) {
            val docId = "note_${note.id}"
            val existing = indexedMap[docId]

            // If note doesn't exist in RAG or has been modified since it was indexed
            if (existing == null || existing.createdAt < note.lastModifiedLocally) {
                Timber.tag("NoteIndexer").i("Indexing note %d ('%s')", note.id, note.title)
                
                // If modifying, delete old chunks first to prevent duplicates
                if (existing != null) {
                    ragPipeline.deleteDocument(docId)
                }

                val doc = Document(
                    id = docId,
                    title = note.title,
                    sourceUri = "note://${note.id}",
                    mimeType = "text/markdown",
                    content = note.content,
                    createdAt = note.lastModifiedLocally
                )
                
                try {
                    ragPipeline.ingest(doc)
                } catch (e: Exception) {
                    Timber.tag("NoteIndexer").e(e, "Failed to ingest note %d", note.id)
                }
            }
        }
    }
}
