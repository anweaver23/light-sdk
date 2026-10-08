package io.github.anweaver23.scriptures.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.anweaver23.scriptures.core.Target
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Where the user left off reading and listening. */
class ProgressStore(private val dataStore: DataStore<Preferences>) {

    val lastRead: Flow<Target.Chapter?> = dataStore.data.map { prefs ->
        prefs[LAST_READ]?.let(Target::fromKey) as? Target.Chapter
    }

    val lastPlayed: Flow<Target?> = dataStore.data.map { prefs -> prefs[LAST_PLAYED]?.let(Target::fromKey) }

    suspend fun setLastRead(target: Target.Chapter) {
        dataStore.edit { it[LAST_READ] = target.key }
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
        val LAST_PLAYED = stringPreferencesKey("last_played")
        val QUEUE = stringPreferencesKey("play_queue")
    }
}
