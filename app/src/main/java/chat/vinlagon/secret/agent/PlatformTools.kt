package chat.vinlagon.secret.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import chat.vinlagon.secret.security.ToolType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

private fun JsonObject.stringValue(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

class RunAppTool : AgentTool {
    override val name = "run_app"
    override val description = "Открывает установленное приложение по packageName через системный launcher intent."
    override val type = ToolType.APP_LAUNCH
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("packageName", buildJsonObject { put("type", "string") }) })
        put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("packageName")) })
    }
    override suspend fun execute(context: ToolExecutionContext, args: JsonObject): ToolResult {
        val app = context.applicationContext ?: return ToolResult(false, "Android context недоступен")
        val packageName = args.stringValue("packageName")?.trim().orEmpty()
        if (!packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) return ToolResult(false, "Некорректный packageName")
        val launch = app.packageManager.getLaunchIntentForPackage(packageName) ?: return ToolResult(false, "Нет доступного launcher intent для $packageName")
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(launch)
        return ToolResult(true, "Запущено приложение $packageName")
    }
}

class ScheduleReminderTool : AgentTool {
    override val name = "run_task"
    override val description = "Планирует локальное напоминание через WorkManager. Не управляет Wi-Fi и настройками устройства."
    override val type = ToolType.SCHEDULE
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("message", buildJsonObject { put("type", "string") })
            put("delayMinutes", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 43200) })
        })
        put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("message")); add(kotlinx.serialization.json.JsonPrimitive("delayMinutes")) })
    }
    override suspend fun execute(context: ToolExecutionContext, args: JsonObject): ToolResult {
        val app = context.applicationContext ?: return ToolResult(false, "Android context недоступен")
        val message = args.stringValue("message")?.take(300)?.takeIf { it.isNotBlank() } ?: return ToolResult(false, "Укажите текст напоминания")
        val delay = args.stringValue("delayMinutes")?.toLongOrNull()?.coerceIn(1, 43_200) ?: return ToolResult(false, "delayMinutes должен быть числом 1..43200")
        val work = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .setInputData(Data.Builder().putString("message", message).build())
            .build()
        WorkManager.getInstance(app).enqueue(work)
        return ToolResult(true, "Напоминание запланировано примерно через $delay мин. WorkManager не гарантирует точное время.")
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("agent_reminders", "Напоминания агента", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = NotificationCompat.Builder(applicationContext, "agent_reminders")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("vinlagonChat")
            .setContentText(inputData.getString("message") ?: "Напоминание")
            .setAutoCancel(true)
            .build()
        return runCatching { NotificationManagerCompat.from(applicationContext).notify(id.hashCode(), notification); Result.success() }
            .getOrElse { Result.failure() }
    }
}

class BuildRunnerTool : AgentTool {
    override val name = "build_runner"
    override val description = "Проверяет доступность внешнего изолированного builder; локальная компиляция приложений на Android не включена."
    override val type = ToolType.BUILD
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("projectPath", buildJsonObject { put("type", "string") }) })
        put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("projectPath")) })
    }
    override suspend fun execute(context: ToolExecutionContext, args: JsonObject) = ToolResult(false, "Сборщик проектов не настроен. Нельзя безопасно собирать и устанавливать APK внутри приложения без отдельного изолированного build service.")
}
