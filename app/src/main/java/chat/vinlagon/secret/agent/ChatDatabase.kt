package chat.vinlagon.secret.agent

import android.content.Context
import android.util.Base64
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom

@Entity(tableName = "chat_messages")
data class StoredChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val createdAtMillis: Long
)

@Dao
interface ChatLogDao {
    @Query("SELECT * FROM chat_messages ORDER BY id ASC") suspend fun loadAll(): List<StoredChatMessage>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(messages: List<StoredChatMessage>)
    @Query("DELETE FROM chat_messages") suspend fun clear()
}

@Database(entities = [StoredChatMessage::class], version = 1, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatLog(): ChatLogDao

    companion object {
        @Volatile private var instance: ChatDatabase? = null

        fun get(context: Context): ChatDatabase = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(context: Context): ChatDatabase {
            System.loadLibrary("sqlcipher")
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val secrets = EncryptedSharedPreferences.create(
                context, "database_secret", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val passphrase = secrets.getString("sqlcipher_passphrase", null) ?: run {
                val generated = ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.encodeToString(it, Base64.NO_WRAP) }
                check(secrets.edit().putString("sqlcipher_passphrase", generated).commit()) { "Could not persist SQLCipher key" }
                generated
            }
            return Room.databaseBuilder(context, ChatDatabase::class.java, "vinlagon_chat.db")
                .openHelperFactory(SupportOpenHelperFactory(Base64.decode(passphrase, Base64.NO_WRAP)))
                .build()
        }
    }
}

suspend fun ChatDatabase.saveTranscript(messages: List<ChatMessage>) = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    val rows = messages.filter { it.content.isNotBlank() && it.role != "tool" }.mapIndexed { index, message ->
        StoredChatMessage(role = message.role, content = message.content, createdAtMillis = now + index)
    }
    withTransaction {
        chatLog().clear()
        chatLog().insertAll(rows)
    }
}
