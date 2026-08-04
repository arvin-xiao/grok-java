package ai.grok.tool.api;

import java.util.concurrent.CompletableFuture;

/**
 * Core tool interface. All tools (bash, file-edit, search, etc.) implement this.
 * Mirrors the Rust `Tool` trait from xai-tool-runtime.
 *
 * @param <I> input type (deserialized from JSON arguments)
 */
public interface Tool<I> {

    /**
     * Returns the tool's definition (name, description, JSON schema) for LLM consumption.
     */
    ToolDefinition definition();

    /**
     * Execute the tool with the given context and parsed input.
     */
    CompletableFuture<ToolResult> execute(ToolCallContext context, I input);

    /**
     * Deserialize raw JSON arguments into the tool's input type.
     */
    I parseArguments(com.fasterxml.jackson.databind.JsonNode arguments);
}
