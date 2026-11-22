package com.example.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "panelpull_prefs")

data class UserPreferences(
    val autoDownloadOnExtract: Boolean = false,
    val aiSmartFilterEnabled: Boolean = true,
    val saveToGalleryPictures: Boolean = true,
    val minImageWidthPx: Int = 240,
    val darkTheme: Boolean = true
)

class UserPreferencesRepository(private val context: Context) {
    private object Keys {
        val AUTO_DOWNLOAD = booleanPreferencesKey("auto_download_on_extract")
        val AI_SMART_FILTER = booleanPreferencesKey("ai_smart_filter")
        val SAVE_TO_GALLERY = booleanPreferencesKey("save_to_gallery_pictures")
        val MIN_WIDTH_PX = intPreferencesKey("min_image_width_px")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
    }

    val preferencesFlow: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
        UserPreferences(
            autoDownloadOnExtract = prefs[Keys.AUTO_DOWNLOAD] ?: false,
            aiSmartFilterEnabled = prefs[Keys.AI_SMART_FILTER] ?: true,
            saveToGalleryPictures = prefs[Keys.SAVE_TO_GALLERY] ?: true,
            minImageWidthPx = prefs[Keys.MIN_WIDTH_PX] ?: 240,
            darkTheme = prefs[Keys.DARK_THEME] ?: true
        )
    }

    suspend fun setAutoDownload(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_DOWNLOAD] = enabled }
    }

    suspend fun setAiSmartFilter(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AI_SMART_FILTER] = enabled }
    }

    suspend fun setSaveToGallery(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SAVE_TO_GALLERY] = enabled }
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DARK_THEME] = enabled }
    }
}
