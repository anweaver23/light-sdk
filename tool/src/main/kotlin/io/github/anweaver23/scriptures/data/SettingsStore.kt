package io.github.anweaver23.scriptures.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.anweaver23.scriptures.core.TextSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** User settings. Only the text size for now. */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    val textSize: Flow<TextSize> = dataStore.data.map { prefs -> TextSize.fromKey(prefs[TEXT_SIZE]) }

    suspend fun setTextSize(size: TextSize) {
        dataStore.edit { it[TEXT_SIZE] = size.name }
    }

    private companion object {
        val TEXT_SIZE = stringPreferencesKey("text_size")
    }
}
