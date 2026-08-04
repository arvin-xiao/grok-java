package ai.grok.tool.api;

/**
 * Unique identifier for a tool.
 */
public record ToolId(String value) {
    public ToolId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ToolId must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
