package ai.grok.tools.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for grok-tool-task module.
 */
class TaskTest {

    // ─── SubagentCoordinator ───────────────────────────────────────

    @Nested
    class SubagentCoordinatorTest {
        private SubagentCoordinator coordinator;

        @BeforeEach
        void setUp() {
            coordinator = new SubagentCoordinator(4, 10);
        }

        @Test
        void spawnShouldCreateActiveHandle() {
            var handle = coordinator.spawn("Analyze code");
            assertNotNull(handle.id());
            assertEquals("Analyze code", handle.task());
            assertEquals(SubagentCoordinator.SubagentState.ACTIVE, handle.state());
            assertNotNull(handle.spawnedAt());
        }

        @Test
        void progressShouldTrackCounts() {
            coordinator.spawn("task1");
            coordinator.spawn("task2");

            var progress = coordinator.progress();
            assertEquals(2, progress.active());
            assertEquals(0, progress.completed());
            assertEquals(10, progress.budget());
            assertFalse(progress.isDone());
        }

        @Test
        void completeShouldUpdateProgress() {
            var handle = coordinator.spawn("task1");
            coordinator.complete(handle.id(), SubagentCoordinator.SubagentResult.success("done"));

            var progress = coordinator.progress();
            assertEquals(0, progress.active());
            assertEquals(1, progress.completed());
            assertEquals(SubagentCoordinator.SubagentState.COMPLETED, handle.state());
            assertEquals("done", handle.result().output());
        }

        @Test
        void failShouldRecordError() {
            var handle = coordinator.spawn("task1");
            coordinator.fail(handle.id(), "something broke");

            assertEquals(SubagentCoordinator.SubagentState.COMPLETED, handle.state());
            assertEquals(SubagentCoordinator.SubagentResult.Status.FAILED, handle.result().status());
            assertEquals("something broke", handle.result().error());
        }

        @Test
        void cancelShouldCancelSpecificTask() {
            var h1 = coordinator.spawn("task1");
            var h2 = coordinator.spawn("task2");

            coordinator.cancel(h1.id());

            assertEquals(SubagentCoordinator.SubagentState.CANCELLED, h1.state());
            assertEquals(SubagentCoordinator.SubagentState.ACTIVE, h2.state());
            assertEquals(1, coordinator.progress().active());
            assertEquals(1, coordinator.progress().completed());
        }

        @Test
        void cancelAllShouldCancelEverything() {
            coordinator.spawn("task1");
            coordinator.spawn("task2");
            coordinator.spawn("task3");

            coordinator.cancelAll();

            assertTrue(coordinator.isCancelled());
            var progress = coordinator.progress();
            assertEquals(0, progress.active());
            assertEquals(3, progress.completed());
        }

        @Test
        void spawnAfterCancelShouldFail() {
            coordinator.cancelAll();
            assertThrows(IllegalStateException.class, () -> coordinator.spawn("too late"));
        }

        @Test
        void shouldEnforceBudget() {
            var small = new SubagentCoordinator(10, 2);
            var h1 = small.spawn("task1");
            small.complete(h1.id(), SubagentCoordinator.SubagentResult.success("ok"));

            var h2 = small.spawn("task2");
            small.complete(h2.id(), SubagentCoordinator.SubagentResult.success("ok"));

            // Budget exhausted (2 completed out of 2)
            assertThrows(IllegalStateException.class, () -> small.spawn("task3"));
        }

        @Test
        void shouldEnforceMaxConcurrent() {
            var limited = new SubagentCoordinator(2, 100);
            limited.spawn("task1");
            limited.spawn("task2");
            assertThrows(IllegalStateException.class, () -> limited.spawn("task3"));
        }

        @Test
        void getHandleShouldReturnCorrectHandle() {
            var handle = coordinator.spawn("find me");
            var found = coordinator.getHandle(handle.id());
            assertTrue(found.isPresent());
            assertEquals("find me", found.get().task());
        }

        @Test
        void getHandleShouldReturnEmptyForUnknown() {
            assertTrue(coordinator.getHandle("nonexistent").isEmpty());
        }

        @Test
        void allHandlesShouldReturnAll() {
            coordinator.spawn("a");
            coordinator.spawn("b");
            assertEquals(2, coordinator.allHandles().size());
        }

        @Test
        void completeUnknownIdShouldThrow() {
            assertThrows(IllegalArgumentException.class,
                    () -> coordinator.complete("fake", SubagentCoordinator.SubagentResult.success("x")));
        }

        @Test
        void completeAfterCompleteShouldBeNoOp() {
            var handle = coordinator.spawn("task1");
            coordinator.complete(handle.id(), SubagentCoordinator.SubagentResult.success("first"));

            // Second complete should be ignored (anti-resurrection)
            coordinator.complete(handle.id(), SubagentCoordinator.SubagentResult.success("second"));

            assertEquals(SubagentCoordinator.SubagentState.COMPLETED, handle.state());
            assertEquals("first", handle.result().output());
            assertEquals(1, coordinator.progress().completed());
        }

        @Test
        void cancelAfterCompleteShouldBeNoOp() {
            var handle = coordinator.spawn("task1");
            coordinator.complete(handle.id(), SubagentCoordinator.SubagentResult.success("done"));

            // Cancel after complete should be ignored
            coordinator.cancel(handle.id());

            assertEquals(SubagentCoordinator.SubagentState.COMPLETED, handle.state());
            assertEquals(1, coordinator.progress().completed());
        }

        @Test
        void completeAfterCancelShouldBeNoOp() {
            var handle = coordinator.spawn("task1");
            coordinator.cancel(handle.id());

            // Complete after cancel should be ignored (anti-resurrection)
            coordinator.complete(handle.id(), SubagentCoordinator.SubagentResult.success("late"));

            assertEquals(SubagentCoordinator.SubagentState.CANCELLED, handle.state());
        }

        @Test
        void isTerminalShouldReturnTrueForCompleted() {
            var handle = coordinator.spawn("task1");
            assertFalse(handle.isTerminal());

            coordinator.complete(handle.id(), SubagentCoordinator.SubagentResult.success("done"));
            assertTrue(handle.isTerminal());
        }

        @Test
        void isTerminalShouldReturnTrueForCancelled() {
            var handle = coordinator.spawn("task1");
            coordinator.cancel(handle.id());
            assertTrue(handle.isTerminal());
        }

        @Test
        void isTerminalShouldReturnTrueForFailed() {
            var handle = coordinator.spawn("task1");
            coordinator.fail(handle.id(), "error");
            assertTrue(handle.isTerminal());
        }

        @Test
        void isTerminalShouldReturnFalseForActive() {
            var handle = coordinator.spawn("task1");
            assertFalse(handle.isTerminal());
        }
    }
}
