package ai.grok.tools.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for grok-tool-scheduler module.
 */
class SchedulerTest {

    // ─── Scheduler ─────────────────────────────────────────────────

    @Nested
    class SchedulerCoreTest {
        private Scheduler scheduler;

        @AfterEach
        void tearDown() {
            if (scheduler != null) {
                scheduler.shutdown();
            }
        }

        @Test
        void scheduleOnceShouldExecuteAfterDelay() throws InterruptedException {
            scheduler = new Scheduler();
            CountDownLatch latch = new CountDownLatch(1);

            String id = scheduler.scheduleOnce("test-task", Duration.ofMillis(100), latch::countDown);
            assertNotNull(id);
            assertTrue(id.startsWith("task-"));

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            // Wait briefly for async occurrence recording
            Thread.sleep(200);
            assertFalse(scheduler.occurrenceLogs().isEmpty());
            assertEquals(Scheduler.OccurrenceStatus.SUCCESS, scheduler.occurrenceLogs().get(0).status());
        }

        @Test
        void scheduleRecurringShouldExecuteMultipleTimes() throws InterruptedException {
            scheduler = new Scheduler();
            CountDownLatch latch = new CountDownLatch(3);

            scheduler.scheduleRecurring("recurring-task", Duration.ofMillis(100), latch::countDown);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            scheduler.cancelAll();
            assertTrue(scheduler.occurrenceLogs().size() >= 3);
        }

        @Test
        void cancelShouldRemoveTask() throws InterruptedException {
            scheduler = new Scheduler();
            String id = scheduler.scheduleOnce("to-cancel", Duration.ofSeconds(60), () -> {
            });

            assertEquals(1, scheduler.activeTasks().size());
            assertTrue(scheduler.cancel(id));
            assertEquals(0, scheduler.activeTasks().size());
        }

        @Test
        void cancelNonExistentShouldReturnFalse() {
            scheduler = new Scheduler();
            assertFalse(scheduler.cancel("nonexistent"));
        }

        @Test
        void cancelAllShouldRemoveAllTasks() {
            scheduler = new Scheduler();
            scheduler.scheduleOnce("t1", Duration.ofSeconds(60), () -> {
            });
            scheduler.scheduleOnce("t2", Duration.ofSeconds(60), () -> {
            });

            assertEquals(2, scheduler.activeTasks().size());
            scheduler.cancelAll();
            assertEquals(0, scheduler.activeTasks().size());
        }

        @Test
        void activeTasksShouldReturnCorrectTypes() {
            scheduler = new Scheduler();
            scheduler.scheduleOnce("one-shot", Duration.ofSeconds(60), () -> {
            });
            scheduler.scheduleRecurring("recurring", Duration.ofSeconds(60), () -> {
            });

            var tasks = scheduler.activeTasks();
            assertEquals(2, tasks.size());

            var types = tasks.stream().map(Scheduler.ScheduledTask::type).toList();
            assertTrue(types.contains(Scheduler.TaskType.ONE_SHOT));
            assertTrue(types.contains(Scheduler.TaskType.RECURRING));
        }

        @Test
        void failedTaskShouldRecordError() throws InterruptedException {
            scheduler = new Scheduler();
            CountDownLatch latch = new CountDownLatch(1);

            scheduler.scheduleOnce("failing", Duration.ofMillis(50), () -> {
                latch.countDown();
                throw new RuntimeException("intentional failure");
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            Thread.sleep(100); // Wait for error recording

            var logs = scheduler.occurrenceLogs();
            assertFalse(logs.isEmpty());
            var lastLog = logs.get(logs.size() - 1);
            assertEquals(Scheduler.OccurrenceStatus.FAILED, lastLog.status());
            assertNotNull(lastLog.error());
        }
    }
}
