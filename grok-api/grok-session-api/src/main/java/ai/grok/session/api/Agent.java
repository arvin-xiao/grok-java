package ai.grok.session.api;

import ai.grok.tool.api.ToolDefinition;

import java.util.List;

/**
 * An AI agent that can engage in conversation, use tools, and stream responses.
 * Mirrors the Rust Agent abstraction from xai-grok-agent.
 */
public interface Agent {

    String id();

    String name();

    /**
     * The system prompt rendered for this agent.
     */
    String systemPrompt();

    /**
     * Tool definitions available to this agent (sent to LLM).
     */
    List<ToolDefinition> toolDefinitions();

    /**
     * Execute a single turn of the agent loop (LLM call + tool execution).
     */
    AgentResponse turn(AgentRequest request);

    /**
     * Stream a turn, emitting events as they occur.
     */
    void streamTurn(AgentRequest request, AgentEventSink sink);
}
