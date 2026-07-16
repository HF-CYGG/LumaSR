package com.lumasr.ui

enum class ResultSaveDestination {
    SAF_DOCUMENT,
    MEDIA_STORE
}

fun resultSaveDestinationForSdk(sdkInt: Int): ResultSaveDestination {
    require(sdkInt >= 0) { "SDK version must be non-negative" }
    return if (sdkInt >= 29) {
        ResultSaveDestination.MEDIA_STORE
    } else {
        ResultSaveDestination.SAF_DOCUMENT
    }
}

data class DocumentSaveItem(
    val sourcePath: String,
    val displayName: String,
    val mimeType: String
) {
    init {
        require(sourcePath.isNotBlank()) { "Document source path must not be blank" }
        require(displayName.isNotBlank()) { "Document display name must not be blank" }
        require(mimeType.isNotBlank()) { "Document MIME type must not be blank" }
    }
}

data class DocumentSavePrompt(
    val requestId: Long,
    val displayName: String,
    val mimeType: String,
    val launched: Boolean
)

class DocumentSaveQueue private constructor(
    private val pendingItems: List<DocumentSaveItem>,
    val savedUris: List<String>,
    private val currentRequestId: Long,
    private val currentPromptLaunched: Boolean,
    val stoppedCount: Int
) {
    val currentItem: DocumentSaveItem?
        get() = pendingItems.firstOrNull()

    val prompt: DocumentSavePrompt?
        get() = currentItem?.let { item ->
            DocumentSavePrompt(
                requestId = currentRequestId,
                displayName = item.displayName,
                mimeType = item.mimeType,
                launched = currentPromptLaunched
            )
        }

    val savedCount: Int
        get() = savedUris.size

    val remainingCount: Int
        get() = pendingItems.size

    val isFinished: Boolean
        get() = pendingItems.isEmpty()

    val wasStopped: Boolean
        get() = stoppedCount > 0

    fun markLaunched(requestId: Long): DocumentSaveQueue {
        if (!matchesCurrent(requestId) || currentPromptLaunched) return this
        return copy(currentPromptLaunched = true)
    }

    fun complete(requestId: Long, destinationUri: String): DocumentSaveQueue {
        if (!matchesCurrent(requestId)) return this
        require(destinationUri.isNotBlank()) { "Saved document URI must not be blank" }
        return copy(
            pendingItems = pendingItems.drop(1),
            savedUris = savedUris + destinationUri,
            currentRequestId = currentRequestId + 1L,
            currentPromptLaunched = false
        )
    }

    fun cancel(requestId: Long): DocumentSaveQueue = stop(requestId)

    fun fail(requestId: Long): DocumentSaveQueue = stop(requestId)

    private fun stop(requestId: Long): DocumentSaveQueue {
        if (!matchesCurrent(requestId)) return this
        return copy(
            pendingItems = emptyList(),
            stoppedCount = pendingItems.size,
            currentPromptLaunched = false
        )
    }

    private fun matchesCurrent(requestId: Long): Boolean {
        return pendingItems.isNotEmpty() && requestId == currentRequestId
    }

    private fun copy(
        pendingItems: List<DocumentSaveItem> = this.pendingItems,
        savedUris: List<String> = this.savedUris,
        currentRequestId: Long = this.currentRequestId,
        currentPromptLaunched: Boolean = this.currentPromptLaunched,
        stoppedCount: Int = this.stoppedCount
    ): DocumentSaveQueue = DocumentSaveQueue(
        pendingItems = pendingItems,
        savedUris = savedUris,
        currentRequestId = currentRequestId,
        currentPromptLaunched = currentPromptLaunched,
        stoppedCount = stoppedCount
    )

    companion object {
        fun start(
            items: List<DocumentSaveItem>,
            firstRequestId: Long
        ): DocumentSaveQueue {
            require(items.isNotEmpty()) { "At least one document is required" }
            require(firstRequestId >= 0L) { "Document request id must be non-negative" }
            return DocumentSaveQueue(
                pendingItems = items.toList(),
                savedUris = emptyList(),
                currentRequestId = firstRequestId,
                currentPromptLaunched = false,
                stoppedCount = 0
            )
        }
    }
}
