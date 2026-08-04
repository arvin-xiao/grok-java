package ai.grok.session.api;

import java.util.List;

/**
 * Response from a single agent turn.
 */
public record AgentResponse(
        String textContent,
        List<ToolCallResult> toolResults,
        int promptTokens,
        int completionTokens
) {
    public boolean hasToolCalls() {
        return toolResults != null && !toolResults.isEmpty();
    }

    public record ToolCallResult(
            String callId,
            String toolName,
            String result
    ) {
    }
}
