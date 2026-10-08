package io.github.anweaver23.scriptures.data

import android.media.MediaMetadataRetriever
import java.io.File

/** Pulls album, title and artist tags so "track07.mp3" tagged "Alma 32" still matches. */
object MediaTagReader : TagReader {
    override fun read(file: File): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            listOf(
                MediaMetadataRetriever.METADATA_KEY_ALBUM,
                MediaMetadataRetriever.METADATA_KEY_TITLE,
                MediaMetadataRetriever.METADATA_KEY_ARTIST,
            ).mapNotNull { retriever.extractMetadata(it) }
                .joinToString(" ")
                .ifBlank { null }
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }
}
