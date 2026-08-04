package ai.grok.session.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A conversation session that manages message history and drives the agent loop.
 * Mirrors the Rust Session Actor from xai-grok-shell.
 */
public interface Session extends AutoCloseable {

    String id();

    SessionState state();

    /**
     * Send a user prompt and get the agent's full response.
     */
    CompletableFuture<PromptResult> prompt(String content);

    /**
     * Send a user prompt with streaming events.
     */
    CompletableFuture<PromptResult> prompt(String content, AgentEventSink sink);

    /**
     * Cancel the current turn.
     */
    void cancel();

    /**
     * Get the full message history.
     */
    List<ChatMessage> history();

    /**
     * Close the session and release resources.
     */
    @Override
    void close();

    /**
     * Session state enumeration.
     */
    enum SessionState {
        IDLE,
        TURN_RUNNING,
        TURN_CANCELLING
    }

    /**
     * Result of a prompt.
     */
    record PromptResult(
            String textContent,
            int totalTurns,
            int totalTokens
    ) {
    }
}
