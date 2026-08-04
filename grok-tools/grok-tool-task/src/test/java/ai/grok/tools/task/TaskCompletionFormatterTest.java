package ai.grok.tools.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TaskCompletionFormatter.
 * Mirrors the Rust `task_completion.rs` tests from xai-grok-tools (2026-08-03 e5478ef sync).
 */
class TaskCompletionFormatterTest {

    private TaskCompletionFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new TaskCompletionFormatter();
    }

    @Nested
    @DisplayName("Bash completion messages")
    class BashCompletion {

        @Test
        @DisplayName("completed task with inline output")
        void completedTaskWithInlineOutput() {
            var task = TaskCompletionFormatter.CompletedTask.bash(
                    "bg-1", "hello world", 11, false, 0, null);
            String msg = formatter.formatBashCompletion(task, "read_file");

            assertTrue(msg.contains("Background task 'bg-1' completed"), msg);
            assertTrue(msg.contains("hello world"), msg);
            assertFalse(msg.contains("exit code"), msg);
        }

        @Test
        @DisplayName("completed task with non-zero exit code")
        void completedTaskWithNonZeroExitCode() {
            var task = TaskCompletionFormatter.CompletedTask.bash(
                    "bg-2", "error output", 12, false, 1, null);
            String msg = formatter.formatBashCompletion(task, null);

            assertTrue(msg.contains("exit code 1"), msg);
        }

        @Test
        @DisplayName("truncated output states real size and file location")
        void truncatedOutputStatesRealSize() {
            String held = "x".repeat(10_000);
            var task = TaskCompletionFormatter.CompletedTask.bash(
                    "bg-3", held, 5_000_000, true, 0, Path.of("/tmp/bg-3.log"));
            String msg = formatter.formatBashCompletion(task, "read_file");

            assertTrue(msg.contains("5000000 bytes total"), msg);
            assertTrue(msg.contains("bg-3.log"), msg);
        }

        @Test
        @DisplayName("unreadable log still points at the file with real size")
        void unreadableLogStillPointsAtFile() {
            // A log that cannot be read produces an empty snapshot with a
            // non-zero total. The completion must still say how big the output
            // is and where to read it.
            var task = TaskCompletionFormatter.CompletedTask.unreadableLog(
                    "bg-unreadable", 123_456, Path.of("/tmp/bg-unreadable.log"));
            String msg = formatter.formatBashCompletion(task, "read_file");

            assertTrue(msg.contains("123456 bytes total"), msg);
            assertTrue(msg.contains("bg-unreadable.log"), msg);
            assertTrue(msg.contains("read_file"), msg);
        }

        @Test
        @DisplayName("unreadable log without read tool hint")
        void unreadableLogWithoutReadToolHint() {
            var task = TaskCompletionFormatter.CompletedTask.unreadableLog(
                    "bg-unreadable", 123_456, Path.of("/tmp/bg-unreadable.log"));
            String msg = formatter.formatBashCompletion(task, null);

            assertTrue(msg.contains("123456 bytes total"), msg);
            assertTrue(msg.contains("bg-unreadable.log"), msg);
            assertFalse(msg.contains("Use "), msg);
        }

        @Test
        @DisplayName("partial output with footer hint includes read tool name")
        void partialOutputWithFooterHint() {
            var task = TaskCompletionFormatter.CompletedTask.bash(
                    "bg-4", "partial content", 50_000, true, 0, Path.of("/tmp/bg-4.log"));
            String msg = formatter.formatBashCompletion(task, "read_file");

            assertTrue(msg.contains("50000 bytes total"), msg);
            assertTrue(msg.contains("read_file"), msg);
            assertTrue(msg.contains("bg-4.log"), msg);
        }
    }

    @Nested
    @DisplayName("Generic completion messages")
    class GenericCompletion {

        @Test
        @DisplayName("completed task with status")
        void completedTaskWithStatus() {
            var task = TaskCompletionFormatter.CompletedTask.generic(
                    "task-1", "result data", 11, false, "SUCCESS");
            String msg = formatter.formatGenericCompletion(task);

            assertTrue(msg.contains("Task 'task-1' completed"), msg);
            assertTrue(msg.contains("SUCCESS"), msg);
            assertTrue(msg.contains("result data"), msg);
        }

        @Test
        @DisplayName("truncated generic output")
        void truncatedGenericOutput() {
            var task = TaskCompletionFormatter.CompletedTask.generic(
                    "task-2", "x".repeat(20_000), 100_000, true, "SUCCESS");
            String msg = formatter.formatGenericCompletion(task);

            assertTrue(msg.contains("100000 bytes total"), msg);
        }

        @Test
        @DisplayName("empty output produces minimal message")
        void emptyOutputProducesMinimalMessage() {
            var task = TaskCompletionFormatter.CompletedTask.generic(
                    "task-3", "", 0, false, "SUCCESS");
            String msg = formatter.formatGenericCompletion(task);

            assertTrue(msg.contains("Task 'task-3' completed"), msg);
            assertTrue(msg.contains("SUCCESS"), msg);
            assertFalse(msg.contains("bytes total"), msg);
        }
    }

    @Nested
    @DisplayName("CompletedTask factory methods")
    class CompletedTaskFactory {

        @Test
        @DisplayName("bash factory creates correct task")
        void bashFactoryCreatesCorrectTask() {
            var task = TaskCompletionFormatter.CompletedTask.bash(
                    "id", "output", 100, true, 0, Path.of("/tmp/log"));
            assertEquals("id", task.taskId());
            assertEquals("output", task.output());
            assertEquals(100, task.totalBytes());
            assertTrue(task.truncated());
            assertEquals(0, task.exitCode());
            assertTrue(task.outputFile().isPresent());
            assertTrue(task.outputFile().get().contains("log"), task.outputFile().get());
        }

        @Test
        @DisplayName("generic factory creates correct task")
        void genericFactoryCreatesCorrectTask() {
            var task = TaskCompletionFormatter.CompletedTask.generic(
                    "id", "output", 100, false, "DONE");
            assertEquals("DONE", task.status());
            assertTrue(task.outputFile().isEmpty());
        }

        @Test
        @DisplayName("unreadableLog factory creates empty output with non-zero total")
        void unreadableLogFactoryCreatesCorrectTask() {
            var task = TaskCompletionFormatter.CompletedTask.unreadableLog(
                    "id", 999, Path.of("/tmp/log"));
            assertEquals("", task.output());
            assertEquals(999, task.totalBytes());
            assertTrue(task.truncated());
            assertEquals(-1, task.exitCode());
        }
    }
}
