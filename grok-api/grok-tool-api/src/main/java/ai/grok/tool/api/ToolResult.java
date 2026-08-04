package ai.grok.tool.api;

/**
 * Result of a tool execution.
 */
public sealed interface ToolResult {

    ToolCallId callId();

    /**
     * Successful execution with output content.
     */
    record Success(ToolCallId callId, String output) implements ToolResult {
        public Success {
            if (output == null) output = "";
        }
    }

    /**
     * Execution failed with an error message.
     */
    record Failure(ToolCallId callId, String error, int exitCode) implements ToolResult {
        public Failure(ToolCallId callId, String error) {
            this(callId, error, 1);
        }
    }

    /**
     * Execution requires user approval before proceeding.
     */
    record NeedsApproval(ToolCallId callId, String reason, String command) implements ToolResult {
    }
}
