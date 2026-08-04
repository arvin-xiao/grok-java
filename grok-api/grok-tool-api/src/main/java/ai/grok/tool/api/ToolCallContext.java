package ai.grok.tool.api;

/**
 * Context provided to a tool during execution.
 */
public record ToolCallContext(
        String workingDirectory,
        String sessionId,
        ToolCallProgress progress
) {
    /**
     * Callback for tools to report progress during long-running operations.
     */
    public interface ToolCallProgress {
        void onOutput(String chunk);

        default void onComplete() {
        }
    }
}
