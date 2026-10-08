package io.github.anweaver23.scriptures.data

import io.github.anweaver23.scriptures.core.Target
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's sorted files, stored by target key in the tool's private files:
 *   library/audio/scriptures/bofm/alma/32.mp3
 *   library/audio/hymns/hymns-1985/85.m4a
 *   library/lyrics/hymns/hymns-1985/85.txt
 * The directory tree is the source of truth; [index] is an in-memory view of it.
 */
class AudioLibrary(filesDir: File) {
    enum class Kind(val dir: String) { Audio("audio"), Lyrics("lyrics") }

    data class Index(val audio: Map<String, File>, val lyrics: Map<String, File>) {
        fun audio(target: Target): File? = audio[target.key]
        fun lyrics(target: Target): File? = lyrics[target.key]
        fun hasAudio(target: Target) = target.key in audio
    }

    private val root = File(filesDir, "library")

    private val _index = MutableStateFlow(scan())
    val index: StateFlow<Index> = _index.asStateFlow()

    fun refresh() {
        _index.value = scan()
    }

    /** Moves [source] into the library as [target], replacing any earlier file for it. */
    fun store(source: File, target: Target, kind: Kind, extension: String) {
        val dest = File(root, "${kind.dir}/${target.key}.$extension")
        dest.parentFile?.mkdirs()
        remove(target, kind)
        if (!source.renameTo(dest)) {
            source.copyTo(dest, overwrite = true)
            source.delete()
        }
    }

    fun remove(target: Target, kind: Kind) {
        val leaf = File(root, "${kind.dir}/${target.key}")
        leaf.parentFile?.listFiles { f -> f.nameWithoutExtension == leaf.name }?.forEach { it.delete() }
    }

    private fun scan(): Index = Index(audio = scan(Kind.Audio), lyrics = scan(Kind.Lyrics))

    private fun scan(kind: Kind): Map<String, File> {
        val base = File(root, kind.dir)
        if (!base.isDirectory) return emptyMap()
        return base.walkTopDown()
            .filter { it.isFile }
            .associateBy { it.relativeTo(base).invariantSeparatorsPath.substringBeforeLast('.') }
    }
}
