package ai.grok.tools.scheduler;

import ai.grok.tool.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Scheduler tool for scheduling recurring or one-time tasks.
 * Mirrors the Rust scheduler tool from xai-grok-tools.
 *
 * <p>Actions:
 * <ul>
 *   <li>schedule_once - Schedule a one-time task with delay</li>
 *   <li>schedule_recurring - Schedule a recurring task</li>
 *   <li>cancel - Cancel a scheduled task</li>
 *   <li>list - List all active tasks</li>
 *   <li>history - Show occurrence log</li>
 * </ul>
 */
public class SchedulerTool implements Tool<SchedulerTool.SchedulerInput> {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Scheduler scheduler;

    public SchedulerTool(Scheduler scheduler) {
        this.scheduler = scheduler;
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
                          "enum": ["schedule_once", "schedule_recurring", "cancel", "list", "history"],
                          "description": "The action to perform"
                        },
                        "task_name": {
                          "type": "string",
                          "description": "Human-readable task name"
                        },
                        "interval_seconds": {
                          "type": "integer",
                          "description": "Interval in seconds (for recurring tasks)"
                        },
                        "delay_seconds": {
                          "type": "integer",
                          "description": "Delay in seconds (for one-time tasks)"
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
        return new ToolDefinition(new ToolId("scheduler"), "scheduler",
                "Schedule and manage recurring/one-time tasks", schema);
    }

    @Override
    public SchedulerInput parseArguments(JsonNode arguments) {
        return new SchedulerInput(
                arguments.get("action").asText(),
                arguments.has("task_name") ? arguments.get("task_name").asText() : null,
                arguments.has("interval_seconds") ? arguments.get("interval_seconds").asInt() : 0,
                arguments.has("delay_seconds") ? arguments.get("delay_seconds").asInt() : 0,
                arguments.has("task_id") ? arguments.get("task_id").asText() : null
        );
    }

    @Override
    public CompletableFuture<ToolResult> execute(ToolCallContext ctx, SchedulerInput input) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return switch (input.action()) {
                    case "schedule_once" -> executeScheduleOnce(input);
                    case "schedule_recurring" -> executeScheduleRecurring(input);
                    case "cancel" -> executeCancel(input);
                    case "list" -> executeList();
                    case "history" -> executeHistory();
                    default -> new ToolResult.Failure(new ToolCallId("scheduler"), "Unknown action: " + input.action());
                };
            } catch (Exception e) {
                return new ToolResult.Failure(new ToolCallId("scheduler"), e.getMessage());
            }
        });
    }

    private ToolResult executeScheduleOnce(SchedulerInput input) {
        if (input.taskName() == null) {
            return new ToolResult.Failure(new ToolCallId("scheduler"), "task_name is required");
        }
        Duration delay = Duration.ofSeconds(input.delaySeconds() > 0 ? input.delaySeconds() : 60);
        String id = scheduler.scheduleOnce(input.taskName(), delay, () -> {
            // Placeholder — in a real implementation, this would trigger an agent turn
        });
        ObjectNode result = mapper.createObjectNode();
        result.put("action", "schedule_once");
        result.put("task_id", id);
        result.put("task_name", input.taskName());
        result.put("delay_seconds", delay.getSeconds());
        return new ToolResult.Success(new ToolCallId("scheduler"), result.toString());
    }

    private ToolResult executeScheduleRecurring(SchedulerInput input) {
        if (input.taskName() == null) {
            return new ToolResult.Failure(new ToolCallId("scheduler"), "task_name is required");
        }
        Duration interval = Duration.ofSeconds(input.intervalSeconds() > 0 ? input.intervalSeconds() : 300);
        String id = scheduler.scheduleRecurring(input.taskName(), interval, () -> {
            // Placeholder
        });
        ObjectNode result = mapper.createObjectNode();
        result.put("action", "schedule_recurring");
        result.put("task_id", id);
        result.put("task_name", input.taskName());
        result.put("interval_seconds", interval.getSeconds());
        return new ToolResult.Success(new ToolCallId("scheduler"), result.toString());
    }

    private ToolResult executeCancel(SchedulerInput input) {
        if (input.taskId() == null) {
            return new ToolResult.Failure(new ToolCallId("scheduler"), "task_id is required");
        }
        boolean cancelled = scheduler.cancel(input.taskId());
        ObjectNode result = mapper.createObjectNode();
        result.put("action", "cancel");
        result.put("task_id", input.taskId());
        result.put("cancelled", cancelled);
        return new ToolResult.Success(new ToolCallId("scheduler"), result.toString());
    }

    private ToolResult executeList() {
        ObjectNode result = mapper.createObjectNode();
        result.put("action", "list");
        var tasksArray = result.putArray("tasks");
        for (Scheduler.ScheduledTask task : scheduler.activeTasks()) {
            ObjectNode taskNode = tasksArray.addObject();
            taskNode.put("id", task.id());
            taskNode.put("name", task.name());
            taskNode.put("type", task.type().name());
            if (task.interval() != null) taskNode.put("interval_seconds", task.interval().getSeconds());
            if (task.delay() != null) taskNode.put("delay_seconds", task.delay().getSeconds());
        }
        return new ToolResult.Success(new ToolCallId("scheduler"), result.toString());
    }

    private ToolResult executeHistory() {
        ObjectNode result = mapper.createObjectNode();
        result.put("action", "history");
        var logsArray = result.putArray("occurrences");
        for (Scheduler.OccurrenceLog entry : scheduler.occurrenceLogs()) {
            ObjectNode logNode = logsArray.addObject();
            logNode.put("task_id", entry.taskId());
            logNode.put("task_name", entry.taskName());
            logNode.put("status", entry.status().name());
            logNode.put("timestamp", entry.timestamp().toString());
            if (entry.error() != null) logNode.put("error", entry.error());
        }
        return new ToolResult.Success(new ToolCallId("scheduler"), result.toString());
    }

    /**
     * Input for the scheduler tool.
     */
    public record SchedulerInput(
            String action,
            String taskName,
            int intervalSeconds,
            int delaySeconds,
            String taskId
    ) {
    }
}
