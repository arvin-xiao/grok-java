package ai.grok.tool.api;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A request to invoke a tool with specific parameters.
 */
public record ToolCall(
        ToolCallId callId,
        String toolName,
        JsonNode arguments
) {
    public ToolCall {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
    }
}
