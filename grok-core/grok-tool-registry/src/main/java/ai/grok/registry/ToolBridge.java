package ai.grok.registry;

import ai.grok.tool.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.StructuredTaskScope;

/**
 * Bridge between the agent/session layer and the tool registry.
 * Handles parallel tool execution using Virtual Threads.
 */
public class ToolBridge {
    private static final Logger log = LoggerFactory.getLogger(ToolBridge.class);
    private final ToolRegistry registry;
    private final String workingDirectory;
    private final String sessionId;

    public ToolBridge(ToolRegistry registry, String workingDirectory, String sessionId) {
        this.registry = registry;
        this.workingDirectory = workingDirectory;
        this.sessionId = sessionId;
    }

    public List<ToolDefinition> toolDefinitions() {
        return registry.definitions();
    }

    /**
     * Execute multiple tool calls in parallel using Virtual Threads.
     */
    public List<ToolResult> executeParallel(List<ToolCall> calls, ToolCallContext.ToolCallProgress progress) {
        var context = new ToolCallContext(workingDirectory, sessionId, progress);
        var vtFactory = Thread.ofVirtual().name("tool-", 0).factory();

        try (var scope = new StructuredTaskScope<ToolResult>("tool-exec", vtFactory)) {
            @SuppressWarnings("unchecked")
            var subtasks = calls.stream()
                    .<StructuredTaskScope.Subtask<ToolResult>>map(call ->
                            scope.fork(() -> {
                                try {
                                    return registry.dispatch(call, context).join();
                                } catch (Exception e) {
                                    return (ToolResult) new ToolResult.Failure(
                                            call.callId(),
                                            "Tool execution failed: " + e.getMessage()
                                    );
                                }
                            })
                    )
                    .toList();

            try {
                scope.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return calls.stream()
                        .<ToolResult>map(call -> new ToolResult.Failure(
                                call.callId(), "Tool execution interrupted"))
                        .toList();
            }

            return subtasks.stream()
                    .map(st -> {
                        try {
                            return st.get();
                        } catch (Exception e) {
                            return (ToolResult) new ToolResult.Failure(
                                    new ToolCallId("unknown"),
                                    "Tool execution failed: " + e.getMessage()
                            );
                        }
                    })
                    .toList();
        }
    }

    /**
     * Execute a single tool call.
     */
    public CompletableFuture<ToolResult> execute(ToolCall call) {
        var context = new ToolCallContext(workingDirectory, sessionId, new ToolCallContext.ToolCallProgress() {
            @Override
            public void onOutput(String chunk) {
                log.debug("[{}] {}", call.toolName(), chunk);
            }
        });
        return registry.dispatch(call, context);
    }
}
