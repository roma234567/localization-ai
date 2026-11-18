package chat.vinlagon.secret.ui

data class ChatAttachment(
    val id: String,
    val name: String,
    val sizeLabel: String,
    val previewText: String = ""
)

data class ModelProfile(
    val id: String,
    val name: String,
    val provider: String,
    val endpoint: String,
    val model: String
)

data class AgentSkill(
    val id: String,
    val command: String,
    val titleEn: String,
    val titleRu: String,
    val descriptionEn: String,
    val descriptionRu: String,
    val promptTemplate: String,
    val enabled: Boolean = true,
    val custom: Boolean = false
)

fun defaultProviderPresets(): List<ModelProfile> = listOf(
    ModelProfile("openai", "OpenAI", "OpenAI", "https://api.openai.com/v1", "gpt-4.1"),
    ModelProfile("anthropic", "Anthropic", "Anthropic", "https://api.anthropic.com/v1", "claude-sonnet-4-5"),
    ModelProfile("gemini", "Gemini", "Google", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-pro"),
    ModelProfile("local", "Local / Ollama", "Local", "http://127.0.0.1:11434/v1", "qwen2.5-coder:14b")
)

fun defaultClaudeCodeSkills(): List<AgentSkill> = listOf(
    AgentSkill(
        id = "plan",
        command = "/plan",
        titleEn = "Plan",
        titleRu = "План",
        descriptionEn = "Break the task into safe, reviewable steps before editing.",
        descriptionRu = "Разбить задачу на безопасные шаги перед правками.",
        promptTemplate = "Create a concise step-by-step implementation plan without making edits yet: "
    ),
    AgentSkill(
        id = "review",
        command = "/review",
        titleEn = "Review",
        titleRu = "Ревью",
        descriptionEn = "Inspect changes for bugs, edge cases, and regressions.",
        descriptionRu = "Проверить изменения на ошибки, граничные случаи и регрессии.",
        promptTemplate = "Review the current workspace changes for bugs, security issues, and edge cases: "
    ),
    AgentSkill(
        id = "commit",
        command = "/commit",
        titleEn = "Commit",
        titleRu = "Коммит",
        descriptionEn = "Draft a clear, conventional commit message from diffs.",
        descriptionRu = "Подготовить понятное сообщение коммита по изменениям.",
        promptTemplate = "Summarize the staged and unstaged changes and draft a concise commit message: "
    ),
    AgentSkill(
        id = "explain",
        command = "/explain",
        titleEn = "Explain",
        titleRu = "Объяснить",
        descriptionEn = "Explain how a file or module works in plain language.",
        descriptionRu = "Объяснить простым языком, как устроен файл или модуль.",
        promptTemplate = "Explain the architecture, data flow, and key responsibilities of: "
    ),
    AgentSkill(
        id = "refactor",
        command = "/refactor",
        titleEn = "Refactor",
        titleRu = "Рефакторинг",
        descriptionEn = "Simplify structure while preserving behavior.",
        descriptionRu = "Упростить структуру, сохранив текущее поведение.",
        promptTemplate = "Refactor the target code for clarity and maintainability without changing behavior: "
    )
)
