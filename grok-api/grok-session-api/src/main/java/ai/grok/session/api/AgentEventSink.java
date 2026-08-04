package ai.grok.session.api;

/**
 * Sink for streaming agent events during a turn.
 */
public interface AgentEventSink {

    /**
     * Text delta from the LLM (streaming token).
     */
    void onTextDelta(String delta);

    /**
     * A tool call is starting.
     */
    void onToolCallStart(String callId, String toolName);

    /**
     * Streaming output from a running tool.
     */
    void onToolOutput(String callId, String chunk);

    /**
     * A tool call completed with final result.
     */
    void onToolCallEnd(String callId, String result);

    /**
     * The turn completed.
     */
    void onTurnComplete();

    /**
     * The turn was cancelled or errored.
     */
    void onError(Throwable error);
}
