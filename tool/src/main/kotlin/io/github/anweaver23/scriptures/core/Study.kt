package io.github.anweaver23.scriptures.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The user's study data: highlights, notes and bookmarks. Stored as versioned JSON in the
// tool's private files dir (see data/StudyStore). Everything here is plain Kotlin for testing.

/** Writes a [VerseRef] as its compact key, "bofm/alma/32/21". */
object VerseRefKeySerializer : KSerializer<VerseRef> {
    override val descriptor = PrimitiveSerialDescriptor("VerseRef", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: VerseRef) = encoder.encodeString(value.key)
    override fun deserialize(decoder: Decoder): VerseRef {
        val key = decoder.decodeString()
        return VerseRef.fromKey(key) ?: throw SerializationException("Bad verse key: $key")
    }
}

/** One end of an annotation: a word in a verse. */
@Serializable
data class WordAnchor(
    @Serializable(with = VerseRefKeySerializer::class) val ref: VerseRef,
    val word: Int,
)

/**
 * A highlight, or a note when [note] is set (notes are highlighted too). [start] and [end] are
 * inclusive and in reading order; both sit in the same chapter, because selection never leaves the
 * chapter on screen. [quote] is the selected text at the time, so lists don't need the book loaded.
 */
@Serializable
data class Annotation(
    val id: String,
    val start: WordAnchor,
    val end: WordAnchor,
    val quote: String,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val chapter: Target.Chapter get() = start.ref.chapterTarget
    val hasNote: Boolean get() = !note.isNullOrBlank()

    /** This annotation as a range within its chapter. */
    val range: WordRange get() = WordRange(WordPos(start.ref.verse, start.word), WordPos(end.ref.verse, end.word))

    fun isIn(chapter: Target.Chapter): Boolean = start.ref.chapterTarget == chapter
}

/** A manual bookmark at a verse. */
@Serializable
data class Bookmark(
    val id: String,
    @Serializable(with = VerseRefKeySerializer::class) val ref: VerseRef,
    val createdAt: Long,
)

@Serializable
data class AnnotationsFile(val version: Int = StudyFormat.VERSION, val annotations: List<Annotation> = emptyList())

@Serializable
data class BookmarksFile(val version: Int = StudyFormat.VERSION, val bookmarks: List<Bookmark> = emptyList())

object StudyFormat {
    /** Bump when the shape changes, and teach [decodeAnnotations]/[decodeBookmarks] to migrate. */
    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encodeAnnotations(list: List<Annotation>): String = json.encodeToString(AnnotationsFile.serializer(), AnnotationsFile(annotations = list))
    fun encodeBookmarks(list: List<Bookmark>): String = json.encodeToString(BookmarksFile.serializer(), BookmarksFile(bookmarks = list))

    fun decodeAnnotations(text: String): List<Annotation> {
        checkVersion(text)
        return json.decodeFromString(AnnotationsFile.serializer(), text).annotations
    }

    fun decodeBookmarks(text: String): List<Bookmark> {
        checkVersion(text)
        return json.decodeFromString(BookmarksFile.serializer(), text).bookmarks
    }

    /** A file from a newer version of the tool can't be read safely; refuse rather than lose data on save. */
    private fun checkVersion(text: String) {
        val version = json.parseToJsonElement(text).jsonObject["version"]?.jsonPrimitive?.int ?: 1
        if (version > VERSION) throw SerializationException("Study file version $version is newer than $VERSION")
    }
}

object Annotations {
    /** Builds an annotation for [range] in [chapter]. */
    fun create(
        id: String,
        chapter: Target.Chapter,
        range: WordRange,
        quote: String,
        note: String?,
        now: Long,
    ) = Annotation(
        id = id,
        start = WordAnchor(VerseRef(chapter.volumeId, chapter.bookId, chapter.chapter, range.start.verse), range.start.word),
        end = WordAnchor(VerseRef(chapter.volumeId, chapter.bookId, chapter.chapter, range.end.verse), range.end.word),
        quote = quote,
        note = note?.trim()?.takeIf { it.isNotEmpty() },
        createdAt = now,
        updatedAt = now,
    )

    /**
     * What "Highlight" does to [range] given the chapter's [existing] annotations:
     * - already fully inside one annotation → nothing to do ([Plan.NoOp]);
     * - otherwise, plain highlights (no note) that overlap or touch [range] are absorbed into one
     *   highlight covering the union. Annotations with notes are never merged or removed, so a note
     *   can't be lost by highlighting over it; they're allowed to overlap the new highlight.
     */
    fun planHighlight(existing: List<Annotation>, range: WordRange, wordCounts: (verse: Int) -> Int): Plan {
        if (existing.any { it.range.contains(range) }) return Plan.NoOp
        var merged = range
        val absorbed = ArrayList<Annotation>()
        // Absorbing one highlight can widen the range into the next, so repeat until stable.
        var changed = true
        while (changed) {
            changed = false
            for (a in existing) {
                if (a.hasNote || a in absorbed) continue
                if (a.range.overlaps(merged) || touches(a.range, merged, wordCounts)) {
                    merged = merged.union(a.range)
                    absorbed += a
                    changed = true
                }
            }
        }
        return Plan.Write(merged, absorbed.map { it.id })
    }

    /** True when [a] ends on the word right before [b] starts (or the other way round). */
    fun touches(a: WordRange, b: WordRange, wordCounts: (verse: Int) -> Int): Boolean =
        next(a.end, wordCounts) == b.start || next(b.end, wordCounts) == a.start

    /** The word after [pos] in reading order, skipping empty verses' ends. */
    private fun next(pos: WordPos, wordCounts: (verse: Int) -> Int): WordPos =
        if (pos.word + 1 < wordCounts(pos.verse)) WordPos(pos.verse, pos.word + 1) else WordPos(pos.verse + 1, 0)

    sealed interface Plan {
        data object NoOp : Plan
        /** Save one highlight covering [range] and delete [removeIds]. */
        data class Write(val range: WordRange, val removeIds: List<String>) : Plan
    }

    /**
     * The annotation a tap on [pos] should open: one with a note wins over a plain highlight,
     * then the newest.
     */
    fun at(chapterAnnotations: List<Annotation>, pos: WordPos): Annotation? =
        chapterAnnotations
            .filter { pos in it.range }
            .maxWithOrNull(compareBy<Annotation> { it.hasNote }.thenBy { it.createdAt })
}

/** "Alma 32:21". Single-chapter books keep the 1 ("Enos 1:3"), as printed. */
fun Catalog.verseLabel(ref: VerseRef): String {
    val book = scriptures.book(ref.bookId)
    val base = when {
        book == null -> "${ref.bookId} ${ref.chapter}"
        book.id == "dc" -> "D&C ${ref.chapter}"
        else -> "${book.title} ${ref.chapter}"
    }
    return "$base:${ref.verse}"
}

/** "Alma 32:21", "Alma 32:21–23", or "Alma 32:43–33:2" for a range. */
fun Catalog.rangeLabel(start: VerseRef, end: VerseRef): String {
    val first = verseLabel(start)
    return when {
        start == end || (start.chapterTarget == end.chapterTarget && start.verse == end.verse) -> first
        start.chapterTarget == end.chapterTarget -> "$first–${end.verse}"
        start.volumeId == end.volumeId && start.bookId == end.bookId -> "$first–${end.chapter}:${end.verse}"
        else -> "$first–${verseLabel(end)}"
    }
}

fun Catalog.label(annotation: Annotation): String = rangeLabel(annotation.start.ref, annotation.end.ref)
