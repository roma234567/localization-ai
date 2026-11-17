package chat.vinlagon.secret.workspace

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class GitHubConfig(val repository: String = "", val branch: String = "main", val token: String = "")

class SecureGitHubConfigStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "github_config_v1",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    fun load() = GitHubConfig(
        prefs.getString("repository", null).orEmpty(),
        prefs.getString("branch", null) ?: "main",
        prefs.getString("token", null).orEmpty()
    )
    fun save(config: GitHubConfig) {
        val repoParts = config.repository.trim().split('/')
        require(repoParts.size == 2 && repoParts.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) }) { "Repository должен быть в формате owner/repo" }
        require(config.branch.isNotBlank() && config.branch.length <= 255 && !config.branch.contains("..") && config.branch.none { it.code < 0x20 || it.code == 0x7f }) { "Некорректная ветка" }
        require(config.token.isNotBlank()) { "Введите fine-grained token" }
        prefs.edit().putString("repository", config.repository.trim()).putString("branch", config.branch.trim()).putString("token", config.token).apply()
    }
}
