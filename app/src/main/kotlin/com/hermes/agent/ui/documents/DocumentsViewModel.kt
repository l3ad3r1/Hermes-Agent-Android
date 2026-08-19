package com.hermes.agent.ui.documents

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.domain.rag.Document
import com.hermes.agent.domain.rag.RagPipeline
import com.hermes.agent.domain.rag.RetrievedChunk
import com.hermes.agent.util.IdGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
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

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<RetrievedChunk>>(emptyList())
    val searchResults: StateFlow<List<RetrievedChunk>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    fun onSearchQueryChanged(q: String) {
        _searchQuery.value = q
        if (q.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isSearching.value = true
            try {
                _searchResults.value = ragPipeline.retrieve(q, limit = 5)
            } finally {
                _isSearching.value = false
            }
        }
    }

    /**
     * Ingest a plain-text document.
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

    /**
     * Ingest a document directly from an Android Storage Access Framework (SAF) URI.
     */
    fun ingestFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                var fileName = "Imported Document"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            fileName = cursor.getString(nameIndex) ?: fileName
                        }
                    }
                }

                val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                }.orEmpty().trim()

                if (content.isNotBlank()) {
                    val now = System.currentTimeMillis()
                    val doc = Document(
                        id = IdGenerator.newId(),
                        title = fileName,
                        sourceUri = uri.toString(),
                        mimeType = context.contentResolver.getType(uri) ?: "text/plain",
                        content = content,
                        createdAt = now,
                    )
                    ragPipeline.ingest(doc)
                }
            } catch (t: Throwable) {
                timber.log.Timber.e(t, "Failed to ingest document from URI $uri")
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            ragPipeline.deleteDocument(id)
        }
    }
}
