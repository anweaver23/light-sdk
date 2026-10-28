package io.github.anweaver23.scriptures.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.anweaver23.scriptures.core.Target
import io.github.anweaver23.scriptures.core.VerseRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Where the user left off reading and listening. */
class ProgressStore(private val dataStore: DataStore<Preferences>) {

    val lastRead: Flow<Target.Chapter?> = dataStore.data.map { prefs ->
        prefs[LAST_READ]?.let(Target::fromKey) as? Target.Chapter
    }

    /**
     * The top verse on screen when reading stopped, as a full reference. Only meaningful when its
     * chapter matches [lastRead]; [lastReadVerse] does that check.
     */
    val lastReadVerse: Flow<VerseRef?> = dataStore.data.map { prefs ->
        val chapter = prefs[LAST_READ]?.let(Target::fromKey) as? Target.Chapter
        val ref = prefs[LAST_READ_VERSE]?.let(VerseRef::fromKey)
        ref?.takeIf { it.chapterTarget == chapter }
    }

    /**
     * The last page read: a chapter or a hymn. Home reopens it on launch. Null for data saved before
     * hymns counted (then [lastRead] stands in).
     */
    val lastPage: Flow<Target?> = dataStore.data.map { prefs -> prefs[LAST_PAGE]?.let(Target::fromKey) }

    val lastPlayed: Flow<Target?> = dataStore.data.map { prefs -> prefs[LAST_PLAYED]?.let(Target::fromKey) }

    suspend fun setLastRead(target: Target.Chapter) {
        dataStore.edit {
            it[LAST_READ] = target.key
            it[LAST_PAGE] = target.key
        }
    }

    /** A hymn page was opened or turned to; it's now the page home reopens. */
    suspend fun setLastHymn(hymn: Target.HymnRef) {
        dataStore.edit { it[LAST_PAGE] = hymn.base.key }
    }

    /** Records the verse at the top of the screen; also makes its chapter the last one read. */
    suspend fun setLastReadVerse(ref: VerseRef) {
        dataStore.edit {
            it[LAST_READ] = ref.chapterTarget.key
            it[LAST_READ_VERSE] = ref.key
            it[LAST_PAGE] = ref.chapterTarget.key
        }
    }

    suspend fun savePlayback(queue: List<Target>, current: Target, positionMs: Long) {
        dataStore.edit {
            it[QUEUE] = queue.joinToString("|") { t -> t.key }
            it[LAST_PLAYED] = current.key
            it[positionKey(current)] = positionMs
        }
    }

    suspend fun savedQueue(): List<Target> =
        dataStore.data.first()[QUEUE].orEmpty().split('|').mapNotNull(Target::fromKey)

    suspend fun position(target: Target): Long = dataStore.data.first()[positionKey(target)] ?: 0L

    suspend fun clearPosition(target: Target) {
        dataStore.edit { it.remove(positionKey(target)) }
    }

    private fun positionKey(target: Target) = longPreferencesKey("pos:${target.key}")

    private companion object {
        val LAST_READ = stringPreferencesKey("last_read")
        val LAST_READ_VERSE = stringPreferencesKey("last_read_verse")
        val LAST_PAGE = stringPreferencesKey("last_page")
        val LAST_PLAYED = stringPreferencesKey("last_played")
        val QUEUE = stringPreferencesKey("play_queue")
    }
}
