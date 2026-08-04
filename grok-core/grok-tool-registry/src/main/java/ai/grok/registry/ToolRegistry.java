package ai.grok.registry;

import ai.grok.tool.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of available tools. Manages tool lifecycle and dispatch.
 */
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private final Map<String, Tool<?>> tools = new ConcurrentHashMap<>();

    public void register(Tool<?> tool) {
        String name = tool.definition().name();
        tools.put(name, tool);
        log.info("Registered tool: {}", name);
    }

    public void registerAll(List<Tool<?>> toolList) {
        toolList.forEach(this::register);
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream()
                .map(Tool::definition)
                .sorted(Comparator.comparing(ToolDefinition::name))
                .toList();
    }

    public Optional<Tool<?>> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * Dispatch a tool call to the appropriate tool implementation.
     */
    public CompletableFuture<ToolResult> dispatch(ToolCall call, ToolCallContext context) {
        Tool<?> tool = tools.get(call.toolName());
        if (tool == null) {
            return CompletableFuture.completedFuture(
                    new ToolResult.Failure(call.callId(), "Unknown tool: " + call.toolName())
            );
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                Object input = tool.parseArguments(call.arguments());
                @SuppressWarnings("unchecked")
                Tool<Object> typedTool = (Tool<Object>) tool;
                return typedTool.execute(context, input).join();
            } catch (Exception e) {
                log.error("Tool {} execution failed", call.toolName(), e);
                return new ToolResult.Failure(call.callId(), e.getMessage());
            }
        });
    }

    public int size() {
        return tools.size();
    }
}
