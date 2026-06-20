package com.hermes.agent.ui.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.domain.rag.Document
import com.hermes.agent.domain.rag.RagPipeline
import com.hermes.agent.util.IdGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    private val ragPipeline: RagPipeline,
) : ViewModel() {

    val documents: StateFlow<List<Document>> = ragPipeline.observeDocuments()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * Ingest a plain-text document. Phase 2 only supports manual text
     * entry; Phase 3 will add SAF-based file picking (PDF/txt/md).
     */
    fun ingestText(title: String, content: String) {
        val safeTitle = title.trim().ifBlank { "Untitled" }
        val safeContent = content.trim()
        if (safeContent.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val doc = Document(
                id = IdGenerator.newId(),
                title = safeTitle,
                sourceUri = "manual://entry/$now",
                mimeType = "text/plain",
                content = safeContent,
                createdAt = now,
            )
            ragPipeline.ingest(doc)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            ragPipeline.deleteDocument(id)
        }
    }
}
