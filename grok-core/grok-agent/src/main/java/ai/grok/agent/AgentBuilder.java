package ai.grok.agent;

import ai.grok.config.GrokConfig;
import ai.grok.registry.ToolBridge;
import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * Builder for constructing GrokAgent instances with fluent API.
 */
public class AgentBuilder {
    private String id;
    private String name = "grok";
    private String systemPrompt;
    private ToolBridge toolBridge;
    private GrokConfig config;
    private ChatLanguageModel chatModel;

    public AgentBuilder id(String id) {
        this.id = id;
        return this;
    }

    public AgentBuilder name(String name) {
        this.name = name;
        return this;
    }

    public AgentBuilder systemPrompt(String prompt) {
        this.systemPrompt = prompt;
        return this;
    }

    public AgentBuilder toolBridge(ToolBridge bridge) {
        this.toolBridge = bridge;
        return this;
    }

    public AgentBuilder config(GrokConfig config) {
        this.config = config;
        return this;
    }

    public AgentBuilder chatModel(ChatLanguageModel model) {
        this.chatModel = model;
        return this;
    }

    public GrokAgent build() {
        if (id == null) id = "agent-" + System.currentTimeMillis();
        if (systemPrompt == null) systemPrompt = defaultSystemPrompt();
        if (config == null) config = GrokConfig.defaults();
        if (toolBridge == null) throw new IllegalStateException("ToolBridge is required");

        return new GrokAgent(id, name, systemPrompt, toolBridge, config, chatModel);
    }

    private String defaultSystemPrompt() {
        return """
                You are Grok, an AI coding assistant built by xAI. You are helpful, harmless, and honest.
                
                You have access to tools that let you interact with the user's development environment.
                Use tools when needed to accomplish tasks. Always explain what you're doing.
                
                Current working directory: %s
                
                Guidelines:
                - Read files before editing them
                - Use search to understand codebases before making changes
                - Run commands to verify your changes work
                - Be concise but thorough in explanations
                """.formatted(config.workingDirectory());
    }
}
