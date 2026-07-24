package com.hermes.agent.data.llm

/**
 * A downloadable on-device GGUF model shown in the Assistant Settings dropdown.
 *
 * @param id        stable key persisted in settings (never shown to the user).
 * @param displayName label for the dropdown.
 * @param fileName  the .gguf filename it is saved as (also the on-disk identity;
 *                  used to detect "already downloaded").
 * @param url       direct HuggingFace `resolve` URL (302s to the CDN).
 * @param sizeBytes real download size, used for the label and the free-space
 *                  pre-check. Verified against HuggingFace `x-linked-size`.
 */
data class DownloadableModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
) {
    /** e.g. "770 MB" / "1.9 GB" for labels. */
    val sizeLabel: String
        get() {
            val mb = sizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0)
            else String.format("%.0f MB", mb)
        }
}

/**
 * The registry of models the user can download. Add entries here as better
 * models become available — the settings dropdown and the download/load paths
 * read from this single list, so no other code needs to change.
 *
 * Every URL + size below was verified live against HuggingFace before shipping
 * (a 404 in the picker would be exactly the untested-data bug the ledger warns
 * about). All are bartowski Q4_K_M quantisations — a good size/quality balance
 * for phones.
 */
object ModelCatalog {
    val MODELS: List<DownloadableModel> = listOf(
        DownloadableModel(
            id = "llama-3.2-1b-q4km",
            displayName = "Llama 3.2 1B Instruct (Q4_K_M)",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeBytes = 807_694_464L,
        ),
        DownloadableModel(
            id = "qwen2.5-1.5b-q4km",
            displayName = "Qwen2.5 1.5B Instruct (Q4_K_M)",
            fileName = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
            sizeBytes = 986_048_768L,
        ),
        DownloadableModel(
            id = "qwen2.5-3b-q4km",
            displayName = "Qwen2.5 3B Instruct (Q4_K_M)",
            fileName = "Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            sizeBytes = 1_929_903_264L,
        ),
        DownloadableModel(
            id = "llama-3.2-3b-q4km",
            displayName = "Llama 3.2 3B Instruct (Q4_K_M)",
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeBytes = 2_019_377_696L,
        ),
    )

    /** The model selected when the user has never picked one. */
    val DEFAULT: DownloadableModel = MODELS.first()

    /** Resolve a persisted id back to a model, falling back to [DEFAULT]. */
    fun byId(id: String): DownloadableModel =
        MODELS.firstOrNull { it.id == id } ?: DEFAULT

    /** The default top-level folder name on shared storage. */
    const val DEFAULT_DIR_NAME = "AI Models"
}
