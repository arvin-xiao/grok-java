package ai.grok.agent;

import ai.grok.config.GrokConfig;
import ai.grok.registry.ToolBridge;
import ai.grok.session.api.Agent;
import ai.grok.session.api.AgentEventSink;
import ai.grok.session.api.AgentRequest;
import ai.grok.session.api.AgentResponse;
import ai.grok.tool.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core agent implementation using LangChain4j for LLM interaction.
 * Implements the ReAct (Reason + Act) conversation loop.
 */
public class GrokAgent implements Agent {
    private static final Logger log = LoggerFactory.getLogger(GrokAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String id;
    private final String name;
    private final String systemPrompt;
    private final ToolBridge toolBridge;
    private final ChatLanguageModel chatModel;
    private final int maxTurns;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public GrokAgent(String id, String name, String systemPrompt,
                     ToolBridge toolBridge, GrokConfig config) {
        this(id, name, systemPrompt, toolBridge, config, null);
    }

    public GrokAgent(String id, String name, String systemPrompt,
                     ToolBridge toolBridge, GrokConfig config,
                     ChatLanguageModel chatModel) {
        this.id = id;
        this.name = name;
        this.systemPrompt = systemPrompt;
        this.toolBridge = toolBridge;
        this.maxTurns = config.maxTurns();

        // Use injected model or build from config
        if (chatModel != null) {
            this.chatModel = chatModel;
        } else {
            var modelCfg = config.model();
            this.chatModel = OpenAiChatModel.builder()
                    .baseUrl(modelCfg.baseUrl())
                    .apiKey(modelCfg.apiKey() != null ? modelCfg.apiKey() : "no-key")
                    .modelName(modelCfg.modelName())
                    .temperature(modelCfg.temperature())
                    .maxTokens(modelCfg.maxTokens())
                    .build();
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String systemPrompt() {
        return systemPrompt;
    }

    @Override
    public List<ToolDefinition> toolDefinitions() {
        return toolBridge.toolDefinitions();
    }

    @Override
    public AgentResponse turn(AgentRequest request) {
        cancelled.set(false);
        List<dev.langchain4j.data.message.ChatMessage> messages = buildMessages(request);
        int turns = 0;

        while (turns < maxTurns && !cancelled.get()) {
            turns++;
            log.debug("Agent turn {} (messages: {})", turns, messages.size());

            // Build chat request with messages
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(messages)
                    .build();

            // Call LLM
            ChatResponse response = chatModel.chat(chatRequest);
            AiMessage aiMsg = response.aiMessage();

            if (aiMsg.hasToolExecutionRequests()) {
                // Execute tools
                var toolCalls = aiMsg.toolExecutionRequests();
                log.info("LLM requested {} tool call(s)", toolCalls.size());

                // Convert to our ToolCall format and execute
                List<ToolCall> calls = toolCalls.stream()
                        .map(tc -> new ToolCall(
                                new ToolCallId(tc.id()),
                                tc.name(),
                                parseJson(tc.arguments())
                        ))
                        .toList();

                var results = toolBridge.executeParallel(calls, new ToolCallContext.ToolCallProgress() {
                    @Override
                    public void onOutput(String chunk) {
                        log.debug("Tool output: {}", chunk);
                    }
                });

                // Add assistant message + tool results to conversation
                messages.add(aiMsg);
                for (var result : results) {
                    String output = switch (result) {
                        case ToolResult.Success s -> s.output();
                        case ToolResult.Failure f -> "ERROR: " + f.error();
                        case ToolResult.NeedsApproval n -> "NEEDS APPROVAL: " + n.reason();
                    };
                    messages.add(ToolExecutionResultMessage.from(
                            result.callId().value(),
                            findToolName(result.callId().value(), calls),
                            output
                    ));
                }
            } else {
                // Final text response
                return new AgentResponse(
                        aiMsg.text() != null ? aiMsg.text() : "",
                        List.of(),
                        0, 0
                );
            }
        }

        return new AgentResponse("[max turns reached]", List.of(), 0, 0);
    }

    @Override
    public void streamTurn(AgentRequest request, AgentEventSink sink) {
        try {
            var response = turn(request);
            sink.onTextDelta(response.textContent());
            sink.onTurnComplete();
        } catch (Exception e) {
            sink.onError(e);
        }
    }

    public void cancel() {
        cancelled.set(true);
    }

    /**
     * Build LangChain4j messages from our ChatMessage format.
     * Uses fully qualified ai.grok.session.api.ChatMessage to avoid ambiguity.
     */
    private List<dev.langchain4j.data.message.ChatMessage> buildMessages(AgentRequest request) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        for (var msg : request.messages()) {
            switch (msg) {
                case ai.grok.session.api.ChatMessage.System s -> messages.add(SystemMessage.from(s.content()));
                case ai.grok.session.api.ChatMessage.User u -> messages.add(UserMessage.from(u.content()));
                case ai.grok.session.api.ChatMessage.Assistant a -> messages.add(AiMessage.from(a.content()));
                case ai.grok.session.api.ChatMessage.Tool t -> messages.add(ToolExecutionResultMessage.from(
                        t.toolCallId(), t.toolName(), t.result()));
            }
        }
        return messages;
    }

    private String findToolName(String callId, List<ToolCall> calls) {
        return calls.stream()
                .filter(c -> c.callId().value().equals(callId))
                .map(ToolCall::toolName)
                .findFirst()
                .orElse("unknown");
    }

    private JsonNode parseJson(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }
}
