package io.github.anweaver23.scriptures.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.anweaver23.scriptures.core.DisplaySettings
import io.github.anweaver23.scriptures.core.HomeLayout
import io.github.anweaver23.scriptures.core.LineSpacing
import io.github.anweaver23.scriptures.core.PlaybackSettings
import io.github.anweaver23.scriptures.core.TextSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** User settings: the Display group (text size, line spacing, invert colours) and autoplay. */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    val display: Flow<DisplaySettings> = dataStore.data.map { prefs ->
        DisplaySettings(
            textSize = TextSize.fromKey(prefs[TEXT_SIZE]),
            lineSpacing = LineSpacing.snap(prefs[LINE_SPACING] ?: LineSpacing.DEFAULT),
            invertColors = prefs[INVERT_COLORS] ?: false,
        )
    }

    val playback: Flow<PlaybackSettings> = dataStore.data.map { prefs ->
        PlaybackSettings(
            autoplayChapters = prefs[AUTOPLAY_CHAPTERS] ?: true,
            autoplayHymns = prefs[AUTOPLAY_HYMNS] ?: true,
        )
    }

    val homeLayout: Flow<HomeLayout> = dataStore.data.map { prefs -> HomeLayout.fromKey(prefs[HOME_LAYOUT]) }

    suspend fun setHomeLayout(layout: HomeLayout) {
        dataStore.edit { it[HOME_LAYOUT] = layout.name }
    }

    suspend fun setAutoplayChapters(on: Boolean) {
        dataStore.edit { it[AUTOPLAY_CHAPTERS] = on }
    }

    suspend fun setAutoplayHymns(on: Boolean) {
        dataStore.edit { it[AUTOPLAY_HYMNS] = on }
    }

    suspend fun setTextSize(size: TextSize) {
        dataStore.edit { it[TEXT_SIZE] = size.name }
    }

    suspend fun setLineSpacing(value: Float) {
        dataStore.edit { it[LINE_SPACING] = LineSpacing.snap(value) }
    }

    suspend fun setInvertColors(invert: Boolean) {
        dataStore.edit { it[INVERT_COLORS] = invert }
    }

    private companion object {
        val TEXT_SIZE = stringPreferencesKey("text_size")
        val LINE_SPACING = floatPreferencesKey("line_spacing")
        val INVERT_COLORS = booleanPreferencesKey("invert_colors")
        val AUTOPLAY_CHAPTERS = booleanPreferencesKey("autoplay_chapters")
        val AUTOPLAY_HYMNS = booleanPreferencesKey("autoplay_hymns")
        val HOME_LAYOUT = stringPreferencesKey("home_layout")
    }
}
