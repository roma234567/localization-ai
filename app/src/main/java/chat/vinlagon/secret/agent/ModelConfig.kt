package chat.vinlagon.secret.agent

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class ApiProvider { OPENAI_COMPATIBLE, ANTHROPIC }

data class ApiModelConfig(
    val provider: ApiProvider = ApiProvider.OPENAI_COMPATIBLE,
    val endpoint: String = "https://api.openai.com/v1/chat/completions",
    val model: String = "gpt-4o-mini",
    val apiKey: String = ""
)

/** API credentials are encrypted with a key backed by Android Keystore. Never log this object. */
class SecureModelConfigStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "model_config_v1",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun load(): ApiModelConfig = ApiModelConfig(
        provider = runCatching { ApiProvider.valueOf(preferences.getString("provider", null) ?: "OPENAI_COMPATIBLE") }.getOrDefault(ApiProvider.OPENAI_COMPATIBLE),
        endpoint = preferences.getString("endpoint", null) ?: "https://api.openai.com/v1/chat/completions",
        model = preferences.getString("model", null) ?: "gpt-4o-mini",
        apiKey = preferences.getString("api_key", null) ?: ""
    )

    fun save(config: ApiModelConfig) {
        require(config.endpoint.startsWith("https://")) { "Используйте HTTPS endpoint" }
        preferences.edit()
            .putString("provider", config.provider.name)
            .putString("endpoint", config.endpoint.trim())
            .putString("model", config.model.trim())
            .putString("api_key", config.apiKey)
            .apply()
    }
}
