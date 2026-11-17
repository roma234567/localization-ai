package chat.vinlagon.secret.agent

/** UI state and events are deliberately independent from Compose, allowing reducer-level tests. */
data class AgentUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isSending: Boolean = false,
    val error: String? = null
)

sealed interface AgentAction {
    data class Restore(val messages: List<ChatMessage>) : AgentAction
    data class SendStarted(val userText: String) : AgentAction
    data class AssistantChunk(val text: String) : AgentAction
    data class ToolMessage(val message: ChatMessage) : AgentAction
    data object SendFinished : AgentAction
    data class Failed(val message: String) : AgentAction
    data object ClearError : AgentAction
}

object AgentReducer {
    fun reduce(state: AgentUiState, action: AgentAction): AgentUiState = when (action) {
        is AgentAction.Restore -> state.copy(messages = action.messages, isSending = false, error = null)
        is AgentAction.SendStarted -> state.copy(
            messages = state.messages + ChatMessage("user", action.userText) + ChatMessage("assistant", ""),
            isSending = true,
            error = null
        )
        is AgentAction.AssistantChunk -> {
            val last = state.messages.lastOrNull()
            if (last?.role != "assistant") state else state.copy(messages = state.messages.dropLast(1) + last.copy(content = last.content + action.text))
        }
        is AgentAction.ToolMessage -> state.copy(messages = state.messages + action.message)
        AgentAction.SendFinished -> state.copy(isSending = false)
        is AgentAction.Failed -> state.copy(isSending = false, error = action.message)
        AgentAction.ClearError -> state.copy(error = null)
    }
}
