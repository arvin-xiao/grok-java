package ai.grok.tools.task;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Formats completion messages for background tasks.
 * Mirrors the Rust `reminders/task_completion.rs` from xai-grok-tools (2026-08-03 e5478ef sync).
 *
 * <p>When a background task completes, this formatter produces a message for the model
 * that includes the task output (possibly truncated) and hints about where to find
 * the full content. It uses {@link TaskLogTruncator} for partial output handling.
 */
public final class TaskCompletionFormatter {

    private final TaskLogTruncator truncator;

    public TaskCompletionFormatter() {
        this(new TaskLogTruncator());
    }

    public TaskCompletionFormatter(TaskLogTruncator truncator) {
        this.truncator = truncator;
    }

    /**
     * Format a bash-style completion message for a completed task.
     *
     * <p>If the output is a partial snapshot of a larger log, the message states
     * the real total size and where to find the rest. Even when the text on hand
     * is empty (unreadable log), the message still reports the total size and
     * points at the output file.
     *
     * @param task the completed task snapshot
     * @param readToolName optional tool name to suggest for reading full content (e.g. "read_file")
     * @return formatted completion message
     */
    public String formatBashCompletion(CompletedTask task, String readToolName) {
        StringBuilder msg = new StringBuilder();
        msg.append("Background task '").append(task.taskId()).append("' completed");

        if (task.exitCode() != 0) {
            msg.append(" with exit code ").append(task.exitCode());
        }
        msg.append(".");

        // Handle output
        if (task.outputFile().isPresent()) {
            String outputFile = task.outputFile().get();

            if (task.output().isEmpty() && task.totalBytes() > 0) {
                // Log could not be read (unreadable file) but we know the size.
                // The completion must still say how big the output is and where to read it.
                msg.append("\n\nOutput: ").append(task.totalBytes()).append(" bytes total");
                msg.append(" (stored in ").append(outputFile).append(")");
                if (readToolName != null) {
                    msg.append(". Use ").append(readToolName).append(" to read the full content.");
                }
            } else if (task.truncated()) {
                // Output was truncated - use truncateWithPreview for partial output
                var partial = TaskLogTruncator.PartialOutput.partOf(task.output(), task.totalBytes());
                String hint = readToolName != null
                        ? "Use " + readToolName + " on " + outputFile + " for full content"
                        : "See " + outputFile + " for full content";

                var result = truncator.truncateWithPreview(partial, truncator.maxLength() / 2, hint);
                msg.append("\n\n").append(result.text());
            } else if (!task.output().isEmpty()) {
                // Complete output, not truncated
                msg.append("\n\n").append(task.output());
            }
        } else if (!task.output().isEmpty()) {
            // No output file, just inline output
            msg.append("\n\n").append(task.output());
        }

        return msg.toString();
    }

    /**
     * Format a generic (non-bash) completion message.
     *
     * @param task the completed task snapshot
     * @return formatted completion message
     */
    public String formatGenericCompletion(CompletedTask task) {
        StringBuilder msg = new StringBuilder();
        msg.append("Task '").append(task.taskId()).append("' completed");

        if (task.status() != null) {
            msg.append(" with status: ").append(task.status());
        }
        msg.append(".");

        if (task.output() != null && !task.output().isEmpty()) {
            if (task.truncated()) {
                var partial = TaskLogTruncator.PartialOutput.partOf(task.output(), task.totalBytes());
                var result = truncator.truncateWithPreview(partial);
                msg.append("\n\n").append(result.text());
            } else {
                msg.append("\n\n").append(task.output());
            }
        }

        return msg.toString();
    }

    /**
     * Snapshot of a completed task for formatting purposes.
     * Mirrors the Rust `CompletedTaskSnapshot` struct.
     */
    public record CompletedTask(
        String taskId,
        String output,
        long totalBytes,
        boolean truncated,
        int exitCode,
        Optional<String> outputFile,
        String status
    ) {
        /**
         * Create a completed task with bash-style output.
         */
        public static CompletedTask bash(String taskId, String output, long totalBytes,
                                         boolean truncated, int exitCode, Path outputFile) {
            return new CompletedTask(
                    taskId, output, totalBytes, truncated, exitCode,
                    outputFile != null ? Optional.of(outputFile.toString()) : Optional.empty(),
                    null
            );
        }

        /**
         * Create a completed task with generic status.
         */
        public static CompletedTask generic(String taskId, String output, long totalBytes,
                                            boolean truncated, String status) {
            return new CompletedTask(
                    taskId, output, totalBytes, truncated, 0,
                    Optional.empty(), status
            );
        }

        /**
         * Create a completed task where the log was unreadable.
         * The output is empty but totalBytes is non-zero.
         */
        public static CompletedTask unreadableLog(String taskId, long totalBytes, Path outputFile) {
            return new CompletedTask(
                    taskId, "", totalBytes, true, -1,
                    outputFile != null ? Optional.of(outputFile.toString()) : Optional.empty(),
                    null
            );
        }
    }
}
