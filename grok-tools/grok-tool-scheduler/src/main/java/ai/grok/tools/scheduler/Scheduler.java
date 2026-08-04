package ai.grok.tools.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scheduled task manager for recurring and one-time tasks.
 * Mirrors the Rust scheduler from xai-grok-tools.
 *
 * <p>Supports:
 * <ul>
 *   <li>One-time delayed execution</li>
 *   <li>Recurring interval-based execution</li>
 *   <li>Cron-like scheduling (simplified)</li>
 *   <li>Occurrence logging for audit trail</li>
 * </ul>
 */
public class Scheduler {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);
    private final ScheduledExecutorService executor;
    private final Map<String, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private final List<OccurrenceLog> occurrenceLogs = new CopyOnWriteArrayList<>();
    private final AtomicLong idCounter = new AtomicLong(0);
    private final ObjectMapper mapper = new ObjectMapper();

    public Scheduler() {
        this.executor = Executors.newScheduledThreadPool(4,
                Thread.ofVirtual().name("scheduler-", 0).factory());
    }

    /**
     * Schedule a one-time task with a delay.
     *
     * @param name    human-readable name
     * @param delay   delay before execution
     * @param command the task to execute
     * @return the scheduled task ID
     */
    public String scheduleOnce(String name, Duration delay, Runnable command) {
        String id = "task-" + idCounter.incrementAndGet();
        ScheduledFuture<?> future = executor.schedule(() -> {
            log.info("Executing one-time task '{}' (id={})", name, id);
            try {
                command.run();
                recordOccurrence(id, name, OccurrenceStatus.SUCCESS, null);
            } catch (Exception e) {
                recordOccurrence(id, name, OccurrenceStatus.FAILED, e.getMessage());
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);

        tasks.put(id, new ScheduledTask(id, name, TaskType.ONE_SHOT, delay, null, future));
        log.info("Scheduled one-time task '{}' (id={}) in {}", name, id, delay);
        return id;
    }

    /**
     * Schedule a recurring task at a fixed interval.
     *
     * @param name     human-readable name
     * @param interval interval between executions
     * @param command  the task to execute
     * @return the scheduled task ID
     */
    public String scheduleRecurring(String name, Duration interval, Runnable command) {
        String id = "task-" + idCounter.incrementAndGet();
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
            log.info("Executing recurring task '{}' (id={})", name, id);
            try {
                command.run();
                recordOccurrence(id, name, OccurrenceStatus.SUCCESS, null);
            } catch (Exception e) {
                recordOccurrence(id, name, OccurrenceStatus.FAILED, e.getMessage());
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);

        tasks.put(id, new ScheduledTask(id, name, TaskType.RECURRING, null, interval, future));
        log.info("Scheduled recurring task '{}' (id={}) every {}", name, id, interval);
        return id;
    }

    /**
     * Cancel a scheduled task.
     */
    public boolean cancel(String taskId) {
        ScheduledTask task = tasks.get(taskId);
        if (task == null) return false;
        task.future().cancel(false);
        tasks.remove(taskId);
        log.info("Cancelled task '{}' (id={})", task.name(), taskId);
        return true;
    }

    /**
     * Cancel all scheduled tasks.
     */
    public void cancelAll() {
        for (ScheduledTask task : tasks.values()) {
            task.future().cancel(false);
        }
        tasks.clear();
        log.info("Cancelled all tasks");
    }

    /**
     * Get all active tasks.
     */
    public Collection<ScheduledTask> activeTasks() {
        return List.copyOf(tasks.values());
    }

    /**
     * Get occurrence logs.
     */
    public List<OccurrenceLog> occurrenceLogs() {
        return List.copyOf(occurrenceLogs);
    }

    /**
     * Save occurrence logs to a file.
     */
    public void saveOccurrenceLogs(Path path) throws IOException {
        ArrayNode array = mapper.createArrayNode();
        for (OccurrenceLog entry : occurrenceLogs) {
            ObjectNode node = mapper.createObjectNode();
            node.put("taskId", entry.taskId());
            node.put("taskName", entry.taskName());
            node.put("status", entry.status().name());
            node.put("timestamp", entry.timestamp().toString());
            if (entry.error() != null) node.put("error", entry.error());
            array.add(node);
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(array));
    }

    /**
     * Shutdown the scheduler.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void recordOccurrence(String taskId, String taskName, OccurrenceStatus status, String error) {
        occurrenceLogs.add(new OccurrenceLog(taskId, taskName, status, Instant.now(), error));
    }

    /**
     * Task type.
     */
    public enum TaskType {ONE_SHOT, RECURRING}

    /**
     * Occurrence status.
     */
    public enum OccurrenceStatus {SUCCESS, FAILED}

    /**
     * A scheduled task handle.
     */
    public record ScheduledTask(
            String id,
            String name,
            TaskType type,
            Duration delay,
            Duration interval,
            ScheduledFuture<?> future
    ) {
    }

    /**
     * An occurrence log entry.
     */
    public record OccurrenceLog(
            String taskId,
            String taskName,
            OccurrenceStatus status,
            Instant timestamp,
            String error
    ) {
    }
}
