package com.example.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "panelpull_prefs")

enum class AiApiProvider(
    val label: String,
    val shortBadge: String,
    val defaultModel: String,
    val defaultBaseUrl: String
) {
    FIREBASE_DEFAULT(
        label = "Padrão do App (Firebase AI)",
        shortBadge = "IA Padrão",
        defaultModel = "",
        defaultBaseUrl = ""
    ),
    GEMINI_API(
        label = "Google Gemini API (Sua chave)",
        shortBadge = "Gemini Key",
        defaultModel = "gemini-2.5-flash",
        defaultBaseUrl = "https://generativelanguage.googleapis.com"
    ),
    OPENAI_COMPATIBLE(
        label = "OpenAI / OpenRouter / Compatível",
        shortBadge = "API Key",
        defaultModel = "gpt-4o-mini",
        defaultBaseUrl = "https://api.openai.com/v1"
    )
}

data class CustomAiSettings(
    val provider: AiApiProvider = AiApiProvider.FIREBASE_DEFAULT,
    val apiKey: String = "",
    val modelName: String = "",
    val baseUrl: String = ""
) {
    val isCustomApiActive: Boolean
        get() = provider != AiApiProvider.FIREBASE_DEFAULT && apiKey.isNotBlank()

    val effectiveModel: String
        get() = modelName.trim().ifBlank { provider.defaultModel }

    val effectiveBaseUrl: String
        get() = baseUrl.trim().ifBlank { provider.defaultBaseUrl }

    val activeBadgeLabel: String
        get() = if (isCustomApiActive) {
            "${provider.shortBadge} • ${ApiKeySecureStore.maskApiKey(apiKey)}"
        } else {
            AiApiProvider.FIREBASE_DEFAULT.shortBadge
        }
}

data class UserPreferences(
    val autoDownloadOnExtract: Boolean = false,
    val aiSmartFilterEnabled: Boolean = true,
    val saveToGalleryPictures: Boolean = true,
    val minImageWidthPx: Int = 240,
    val darkTheme: Boolean = true,
    val customAiSettings: CustomAiSettings = CustomAiSettings()
)

class UserPreferencesRepository(private val context: Context) {
    private object Keys {
        val AUTO_DOWNLOAD = booleanPreferencesKey("auto_download_on_extract")
        val AI_SMART_FILTER = booleanPreferencesKey("ai_smart_filter")
        val SAVE_TO_GALLERY = booleanPreferencesKey("save_to_gallery_pictures")
        val MIN_WIDTH_PX = intPreferencesKey("min_image_width_px")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val AI_PROVIDER = stringPreferencesKey("custom_ai_provider")
        val AI_API_KEY_ENC = stringPreferencesKey("custom_ai_api_key_enc")
        val AI_MODEL_NAME = stringPreferencesKey("custom_ai_model_name")
        val AI_BASE_URL = stringPreferencesKey("custom_ai_base_url")
    }

    val preferencesFlow: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
        val providerName = prefs[Keys.AI_PROVIDER]
        val provider = AiApiProvider.entries.firstOrNull { it.name == providerName }
            ?: AiApiProvider.FIREBASE_DEFAULT
        val encryptedKey = prefs[Keys.AI_API_KEY_ENC].orEmpty()
        val decryptedKey = if (encryptedKey.isNotBlank()) {
            ApiKeySecureStore.decrypt(encryptedKey)
        } else {
            ""
        }
        UserPreferences(
            autoDownloadOnExtract = prefs[Keys.AUTO_DOWNLOAD] ?: false,
            aiSmartFilterEnabled = prefs[Keys.AI_SMART_FILTER] ?: true,
            saveToGalleryPictures = prefs[Keys.SAVE_TO_GALLERY] ?: true,
            minImageWidthPx = prefs[Keys.MIN_WIDTH_PX] ?: 240,
            darkTheme = prefs[Keys.DARK_THEME] ?: true,
            customAiSettings = CustomAiSettings(
                provider = if (decryptedKey.isBlank()) AiApiProvider.FIREBASE_DEFAULT else provider,
                apiKey = decryptedKey,
                modelName = prefs[Keys.AI_MODEL_NAME].orEmpty(),
                baseUrl = prefs[Keys.AI_BASE_URL].orEmpty()
            )
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

    suspend fun saveCustomAiSettings(settings: CustomAiSettings) {
        val cleanKey = settings.apiKey.trim()
        val effectiveProvider = if (cleanKey.isBlank()) {
            AiApiProvider.FIREBASE_DEFAULT
        } else {
            settings.provider
        }
        context.dataStore.edit { prefs ->
            prefs[Keys.AI_PROVIDER] = effectiveProvider.name
            if (effectiveProvider == AiApiProvider.FIREBASE_DEFAULT || cleanKey.isBlank()) {
                prefs.remove(Keys.AI_API_KEY_ENC)
                prefs.remove(Keys.AI_MODEL_NAME)
                prefs.remove(Keys.AI_BASE_URL)
            } else {
                prefs[Keys.AI_API_KEY_ENC] = ApiKeySecureStore.encrypt(cleanKey)
                prefs[Keys.AI_MODEL_NAME] = settings.modelName.trim()
                prefs[Keys.AI_BASE_URL] = settings.baseUrl.trim()
            }
        }
    }

    suspend fun clearCustomAiSettings() {
        context.dataStore.edit { prefs ->
            prefs[Keys.AI_PROVIDER] = AiApiProvider.FIREBASE_DEFAULT.name
            prefs.remove(Keys.AI_API_KEY_ENC)
            prefs.remove(Keys.AI_MODEL_NAME)
            prefs.remove(Keys.AI_BASE_URL)
        }
    }
}
