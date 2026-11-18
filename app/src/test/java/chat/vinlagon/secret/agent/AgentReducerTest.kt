package chat.vinlagon.secret.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentReducerTest {
    @Test fun chunksAppendOnlyToLastAssistant() {
        val sending = AgentReducer.reduce(AgentUiState(), AgentAction.SendStarted("hello"))
        val first = AgentReducer.reduce(sending, AgentAction.AssistantChunk("Hi"))
        val second = AgentReducer.reduce(first, AgentAction.AssistantChunk(" there"))
        assertEquals("Hi there", second.messages.last().content)
        assertEquals(true, second.isSending)
    }
}
