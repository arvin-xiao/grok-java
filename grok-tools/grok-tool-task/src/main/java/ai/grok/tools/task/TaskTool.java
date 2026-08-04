package ai.grok.tools.task;

import ai.grok.tool.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Task tool for spawning and managing subagent tasks.
 * Mirrors the Rust task tool from xai-grok-tools.
 *
 * <p>Actions:
 * <ul>
 *   <li>spawn - Create a new subagent task</li>
 *   <li>status - Check progress of subagent tasks</li>
 *   <li>cancel - Cancel a running subagent task</li>
 *   <li>cancel_all - Cancel all running tasks</li>
 * </ul>
 */
public class TaskTool implements Tool<TaskTool.TaskInput> {

    private static final Logger log = LoggerFactory.getLogger(TaskTool.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final SubagentCoordinator coordinator;

    public TaskTool(SubagentCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public ToolDefinition definition() {
        JsonNode schema;
        try {
            schema = mapper.readTree("""
                    {
                      "type": "object",
                      "properties": {
                        "action": {
                          "type": "string",
                          "enum": ["spawn", "status", "cancel", "cancel_all"],
                          "description": "The action to perform"
                        },
                        "task_description": {
                          "type": "string",
                          "description": "Task description (for spawn action)"
                        },
                        "task_id": {
                          "type": "string",
                          "description": "Task ID (for cancel action)"
                        }
                      },
                      "required": ["action"]
                    }
                    """);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new ToolDefinition(new ToolId("task"), "task",
                "Manage subagent tasks (spawn, status, cancel)", schema);
    }

    @Override
    public TaskInput parseArguments(JsonNode arguments) {
        String action = arguments.get("action").asText();
        String taskDescription = arguments.has("task_description")
                ? arguments.get("task_description").asText() : null;
        String taskId = arguments.has("task_id")
                ? arguments.get("task_id").asText() : null;
        return new TaskInput(action, taskDescription, taskId);
    }

    @Override
    public CompletableFuture<ToolResult> execute(ToolCallContext ctx, TaskInput input) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return switch (input.action()) {
                    case "spawn" -> executeSpawn(input);
                    case "status" -> executeStatus();
                    case "cancel" -> executeCancel(input);
                    case "cancel_all" -> executeCancelAll();
                    default -> new ToolResult.Failure(new ToolCallId("task"), "Unknown action: " + input.action());
                };
            } catch (Exception e) {
                return new ToolResult.Failure(new ToolCallId("task"), e.getMessage());
            }
        });
    }

    private ToolResult executeSpawn(TaskInput input) {
        if (input.taskDescription() == null || input.taskDescription().isBlank()) {
            return new ToolResult.Failure(new ToolCallId("task"), "task_description is required for spawn");
        }
        SubagentCoordinator.SubagentHandle handle = coordinator.spawn(input.taskDescription());
        ObjectNode result = mapper.createObjectNode();
        result.put("action", "spawn");
        result.put("task_id", handle.id());
        result.put("status", handle.state().name());
        return new ToolResult.Success(new ToolCallId("task"), result.toString());
    }

    private ToolResult executeStatus() {
        SubagentCoordinator.ProgressSummary progress = coordinator.progress();
        ObjectNode result = mapper.createObjectNode();
        result.put("action", "status");
        result.put("pending", progress.pending());
        result.put("active", progress.active());
        result.put("completed", progress.completed());
        result.put("budget", progress.budget());
        result.put("cancelled", progress.cancelled());

        var handlesArray = result.putArray("tasks");
        for (SubagentCoordinator.SubagentHandle h : coordinator.allHandles()) {
            ObjectNode taskNode = handlesArray.addObject();
            taskNode.put("id", h.id());
            taskNode.put("state", h.state().name());
            taskNode.put("task", h.task());
        }
        return new ToolResult.Success(new ToolCallId("task"), result.toString());
    }

    private ToolResult executeCancel(TaskInput input) {
        if (input.taskId() == null) {
            return new ToolResult.Failure(new ToolCallId("task"), "task_id is required for cancel");
        }
        coordinator.cancel(input.taskId());
        ObjectNode result = mapper.createObjectNode();
        result.put("action", "cancel");
        result.put("task_id", input.taskId());
        return new ToolResult.Success(new ToolCallId("task"), result.toString());
    }

    private ToolResult executeCancelAll() {
        coordinator.cancelAll();
        ObjectNode result = mapper.createObjectNode();
        result.put("action", "cancel_all");
        result.put("message", "All tasks cancelled");
        return new ToolResult.Success(new ToolCallId("task"), result.toString());
    }

    /**
     * Input for the task tool.
     */
    public record TaskInput(String action, String taskDescription, String taskId) {
    }
}
