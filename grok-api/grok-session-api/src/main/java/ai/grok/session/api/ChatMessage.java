package ai.grok.session.api;

/**
 * A message in the conversation.
 */
public sealed interface ChatMessage {

    record System(String content) implements ChatMessage {
    }

    record User(String content) implements ChatMessage {
    }

    record Assistant(String content, java.util.List<ToolCallInfo> toolCalls) implements ChatMessage {
        public Assistant(String content) {
            this(content, java.util.List.of());
        }
    }

    record Tool(String toolCallId, String toolName, String result) implements ChatMessage {
    }

    /**
     * Tool call info from an assistant response.
     */
    record ToolCallInfo(String id, String name, String argumentsJson) {
    }
}
