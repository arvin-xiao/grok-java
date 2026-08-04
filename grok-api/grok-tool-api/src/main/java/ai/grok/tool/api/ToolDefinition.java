package ai.grok.tool.api;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON Schema definition of a tool, sent to the LLM so it knows
 * how to invoke this tool.
 */
public record ToolDefinition(
        ToolId id,
        String name,
        String description,
        JsonNode inputSchema
) {
    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Tool description must not be blank");
        }
    }
}
