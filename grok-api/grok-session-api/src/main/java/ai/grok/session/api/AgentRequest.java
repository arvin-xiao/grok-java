package ai.grok.session.api;

import java.util.List;

/**
 * Request to an agent for a single conversation turn.
 */
public record AgentRequest(
        String sessionId,
        List<ChatMessage> messages
) {
}
