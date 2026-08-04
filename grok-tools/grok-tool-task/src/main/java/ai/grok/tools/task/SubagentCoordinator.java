package ai.grok.tools.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Subagent coordinator managing the lifecycle of child agents.
 * Mirrors the Rust SubagentCoordinator from xai-grok-tools.
 *
 * <p>Manages pending/active/completed lifecycle for sub-agents,
 * with progress tracking, cancellation propagation, and budget control.
 */
public class SubagentCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SubagentCoordinator.class);

    private final Map<String, SubagentHandle> subagents = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(0);
    private final int maxConcurrent;
    private final int budget;
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final AtomicInteger completedCount = new AtomicInteger(0);
    private volatile boolean cancelled = false;

    public SubagentCoordinator(int maxConcurrent, int budget) {
        this.maxConcurrent = maxConcurrent;
        this.budget = budget;
    }

    /**
     * Spawn a new subagent task.
     *
     * @param task the task description / prompt for the subagent
     * @return a handle to track the subagent
     */
    public SubagentHandle spawn(String task) {
        if (cancelled) {
            throw new IllegalStateException("Coordinator is cancelled");
        }
        if (completedCount.get() + activeCount.get() >= budget) {
            throw new IllegalStateException("Budget exhausted: " + budget);
        }
        if (activeCount.get() >= maxConcurrent) {
            throw new IllegalStateException("Max concurrent subagents reached: " + maxConcurrent);
        }

        String id = "subagent-" + idCounter.incrementAndGet();
        SubagentHandle handle = new SubagentHandle(id, task);
        subagents.put(id, handle);
        activeCount.incrementAndGet();
        handle.markActive();

        log.info("Spawned subagent '{}' (active={}, completed={}, budget={})",
                id, activeCount.get(), completedCount.get(), budget);
        return handle;
    }

    /**
     * Mark a subagent as completed.
     * Mirrors the Rust fix from 2026-08-03:
     * "don't resurrect finished background tasks as Running when completion arrives first"
     *
     * <p>If the task is already in a terminal state (COMPLETED, CANCELLED, FAILED),
     * this method is a no-op to prevent resurrection.
     */
    public void complete(String id, SubagentResult result) {
        SubagentHandle handle = subagents.get(id);
        if (handle == null) {
            throw new IllegalArgumentException("Unknown subagent: " + id);
        }

        // Guard against resurrection: if already terminal, don't update
        if (handle.isTerminal()) {
            log.debug("Subagent '{}' already in terminal state {}, ignoring completion",
                    id, handle.state());
            return;
        }

        handle.markCompleted(result);
        activeCount.decrementAndGet();
        completedCount.incrementAndGet();
        log.info("Subagent '{}' completed with status: {}", id, result.status());
    }

    /**
     * Mark a subagent as failed.
     */
    public void fail(String id, String error) {
        complete(id, new SubagentResult(SubagentResult.Status.FAILED, null, error));
    }

    /**
     * Cancel all active subagents.
     */
    public void cancelAll() {
        cancelled = true;
        for (SubagentHandle handle : subagents.values()) {
            if (handle.state() == SubagentState.ACTIVE) {
                handle.markCancelled();
                activeCount.decrementAndGet();
                completedCount.incrementAndGet();
            }
        }
        log.info("Cancelled all subagents (total completed: {})", completedCount.get());
    }

    /**
     * Cancel a specific subagent.
     * Only cancels if not already in a terminal state.
     */
    public void cancel(String id) {
        SubagentHandle handle = subagents.get(id);
        if (handle != null && !handle.isTerminal() && handle.state() == SubagentState.ACTIVE) {
            handle.markCancelled();
            activeCount.decrementAndGet();
            completedCount.incrementAndGet();
        }
    }

    /**
     * Get the current progress summary.
     */
    public ProgressSummary progress() {
        int pending = (int) subagents.values().stream()
                .filter(h -> h.state() == SubagentState.PENDING).count();
        int active = activeCount.get();
        int completed = completedCount.get();
        return new ProgressSummary(pending, active, completed, budget, cancelled);
    }

    /**
     * Get a handle by ID.
     */
    public Optional<SubagentHandle> getHandle(String id) {
        return Optional.ofNullable(subagents.get(id));
    }

    /**
     * Get all handles.
     */
    public Collection<SubagentHandle> allHandles() {
        return List.copyOf(subagents.values());
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Subagent lifecycle states.
     */
    public enum SubagentState {PENDING, ACTIVE, COMPLETED, CANCELLED, FAILED}

    /**
     * Handle to a spawned subagent.
     */
    public static class SubagentHandle {
        private final String id;
        private final String task;
        private final Instant spawnedAt;
        private volatile SubagentState state;
        private volatile SubagentResult result;

        public SubagentHandle(String id, String task) {
            this.id = id;
            this.task = task;
            this.spawnedAt = Instant.now();
            this.state = SubagentState.PENDING;
        }

        void markActive() {
            this.state = SubagentState.ACTIVE;
        }

        /**
         * Mark as completed only if not already terminal.
         * Returns true if the state was actually changed.
         */
        synchronized void markCompleted(SubagentResult result) {
            if (isTerminal()) {
                return; // Don't resurrect
            }
            this.state = SubagentState.COMPLETED;
            this.result = result;
        }

        synchronized void markCancelled() {
            if (isTerminal()) {
                return; // Don't resurrect
            }
            this.state = SubagentState.CANCELLED;
            this.result = new SubagentResult(SubagentResult.Status.CANCELLED, null, "Cancelled");
        }

        /**
         * Check if this handle is in a terminal state.
         * Terminal states: COMPLETED, CANCELLED, FAILED.
         */
        public boolean isTerminal() {
            return state == SubagentState.COMPLETED
                    || state == SubagentState.CANCELLED
                    || state == SubagentState.FAILED;
        }

        public String id() {
            return id;
        }

        public String task() {
            return task;
        }

        public Instant spawnedAt() {
            return spawnedAt;
        }

        public SubagentState state() {
            return state;
        }

        public SubagentResult result() {
            return result;
        }
    }

    /**
     * Result from a completed subagent.
     */
    public record SubagentResult(
            Status status,
            String output,
            String error
    ) {
        public static SubagentResult success(String output) {
            return new SubagentResult(Status.SUCCESS, output, null);
        }

        public enum Status {SUCCESS, FAILED, CANCELLED}
    }

    /**
     * Progress summary.
     */
    public record ProgressSummary(
            int pending,
            int active,
            int completed,
            int budget,
            boolean cancelled
    ) {
        public int total() {
            return pending + active + completed;
        }

        public boolean isDone() {
            return active == 0 && pending == 0;
        }
    }
}
