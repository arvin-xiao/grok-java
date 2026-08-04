package ai.grok.tool.api;

/**
 * Unique identifier for a specific tool invocation (call instance).
 */
public record ToolCallId(String value) {
    public ToolCallId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ToolCallId must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
