package ai.grok.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for grok-workflow module.
 */
class WorkflowTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ─── Journal ───────────────────────────────────────────────────

    @Nested
    class JournalTest {

        @Test
        void emptyJournalShouldNotCoverAnySeq() {
            var journal = new Journal();
            assertFalse(journal.covers(0));
            assertFalse(journal.covers(1));
            assertEquals(0, journal.highestSeq());
        }

        @Test
        void shouldRecordAndRetrieveEntries() {
            var journal = new Journal();
            var node = mapper.createObjectNode().put("result", "ok");
            journal.record(0, "phase_complete", "abc123", node);

            assertTrue(journal.covers(0));
            assertFalse(journal.covers(1));
            assertEquals(0, journal.highestSeq());

            var entry = journal.get(0);
            assertTrue(entry.isPresent());
            assertEquals("phase_complete", entry.get().kind());
            assertEquals("abc123", entry.get().hash());
        }

        @Test
        void shouldTrackHighestSeq() {
            var journal = new Journal();
            journal.record(0, "a", "h0", mapper.createObjectNode());
            journal.record(5, "b", "h5", mapper.createObjectNode());
            journal.record(3, "c", "h3", mapper.createObjectNode());

            assertEquals(5, journal.highestSeq());
            assertEquals(3, journal.entries().size());
        }

        @Test
        void shouldSaveAndLoad(@TempDir Path tempDir) throws IOException {
            var journal = new Journal();
            journal.record(0, "phase", "hash0", mapper.createObjectNode().put("data", "test"));
            journal.record(1, "agent", "hash1", mapper.createObjectNode().put("calls", 3));

            Path file = tempDir.resolve("journal.json");
            journal.save(file);
            assertTrue(Files.exists(file));

            var loaded = Journal.load(file);
            assertEquals(2, loaded.entries().size());
            assertTrue(loaded.covers(0));
            assertTrue(loaded.covers(1));
            assertEquals("phase", loaded.get(0).get().kind());
        }

        @Test
        void loadFromNonExistentFileShouldReturnEmpty() throws IOException {
            var loaded = Journal.load(Path.of("/nonexistent/journal.json"));
            assertEquals(0, loaded.entries().size());
        }
    }

    // ─── WorkflowRunParams ─────────────────────────────────────────

    @Nested
    class WorkflowRunParamsTest {

        @Test
        void shouldRejectBlankScript() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WorkflowRunParams("", null, null, 10, null));
        }

        @Test
        void shouldDefaultBudgetToTen() {
            var params = WorkflowRunParams.builder().script("test").build();
            assertEquals(10, params.agentBudget());
        }

        @Test
        void shouldDefaultCancelToken() {
            var params = WorkflowRunParams.builder().script("test").build();
            assertNotNull(params.cancel());
            assertFalse(params.cancel().isCancelled());
        }

        @Test
        void cancellationTokenShouldWork() {
            var token = new WorkflowRunParams.CancellationToken();
            assertFalse(token.isCancelled());

            token.cancel();
            assertTrue(token.isCancelled());
            assertThrows(WorkflowRunParams.WorkflowCancelledException.class, token::throwIfCancelled);
        }
    }

    // ─── WorkflowEngine ────────────────────────────────────────────

    @Nested
    class WorkflowEngineTest {
        private WorkflowEngine engine;

        @BeforeEach
        void setUp() {
            engine = new WorkflowEngine();
        }

        @Test
        void shouldRunSimpleWorkflow() throws Exception {
            ObjectNode def = mapper.createObjectNode();
            def.put("name", "test-workflow");
            var phases = def.putArray("phases");
            var phase1 = phases.addObject();
            phase1.put("name", "analyze");
            phase1.put("action", "analyze_code");

            var params = WorkflowRunParams.builder()
                    .script(mapper.writeValueAsString(def))
                    .agentBudget(5)
                    .build();

            var outcome = engine.run(params);
            assertEquals(WorkflowOutcome.Status.SUCCESS, outcome.status());
            assertEquals(1, outcome.phaseResults().size());
            assertEquals("analyze", outcome.phaseResults().get(0).phaseName());
        }

        @Test
        void shouldHandleInvalidScript() {
            var params = WorkflowRunParams.builder()
                    .script("{\"no_phases\": true}")
                    .build();

            var outcome = engine.run(params);
            assertEquals(WorkflowOutcome.Status.ERROR, outcome.status());
        }

        @Test
        void shouldRespectCancellation() throws Exception {
            var cancel = new WorkflowRunParams.CancellationToken();
            cancel.cancel();

            ObjectNode def = mapper.createObjectNode();
            var phases = def.putArray("phases");
            phases.addObject().put("name", "p1").put("action", "do");

            var params = new WorkflowRunParams(
                    mapper.writeValueAsString(def), null, new Journal(), 10, cancel
            );

            var outcome = engine.run(params);
            assertEquals(WorkflowOutcome.Status.CANCELLED, outcome.status());
        }

        @Test
        void shouldReplayFromJournal() throws Exception {
            Journal journal = new Journal();
            journal.record(0, "phase_complete", "cached",
                    mapper.createObjectNode().put("replayed", true));

            ObjectNode def = mapper.createObjectNode();
            var phases = def.putArray("phases");
            phases.addObject().put("name", "cached-phase").put("action", "do");
            phases.addObject().put("name", "new-phase").put("action", "do");

            var params = WorkflowRunParams.builder()
                    .script(mapper.writeValueAsString(def))
                    .journal(journal)
                    .agentBudget(10)
                    .build();

            var outcome = engine.run(params);
            assertEquals(WorkflowOutcome.Status.SUCCESS, outcome.status());
            assertEquals(2, outcome.phaseResults().size());
            // First phase should be replayed from journal
            assertEquals("cached-phase", outcome.phaseResults().get(0).phaseName());
        }

        @Test
        void validateShouldAcceptValidScript() throws Exception {
            ObjectNode def = mapper.createObjectNode();
            def.put("name", "valid");
            def.put("description", "A test workflow");
            var phases = def.putArray("phases");
            phases.addObject().put("name", "step1").put("action", "run");

            var report = engine.validate(mapper.writeValueAsString(def));
            assertTrue(report.valid());
            assertNotNull(report.meta());
            assertEquals("valid", report.meta().name());
        }

        @Test
        void validateShouldRejectMissingPhases() {
            var report = engine.validate("{\"name\": \"bad\"}");
            assertFalse(report.valid());
            assertFalse(report.issues().isEmpty());
        }

        @Test
        void validateShouldRejectInvalidJson() {
            var report = engine.validate("not json at all");
            assertFalse(report.valid());
        }
    }

    // ─── WorkflowMeta ──────────────────────────────────────────────

    @Nested
    class WorkflowMetaTest {
        @Test
        void shouldHoldMetadata() {
            var meta = new WorkflowMeta(
                    "build", "Build project", "When user asks to build",
                    java.util.List.of(new WorkflowMeta.PhaseMeta("compile", "Compile", "coder", false))
            );
            assertEquals("build", meta.name());
            assertEquals(1, meta.phases().size());
            assertEquals("compile", meta.phases().get(0).name());
        }
    }

    // ─── ValidationReport ──────────────────────────────────────────

    @Nested
    class ValidationReportTest {
        @Test
        void okShouldBeValid() {
            var meta = new WorkflowMeta("t", "d", "w", java.util.List.of());
            var report = ValidationReport.ok(meta);
            assertTrue(report.valid());
            assertTrue(report.issues().isEmpty());
        }

        @Test
        void withErrorsShouldBeInvalid() {
            var report = ValidationReport.withErrors(
                    java.util.List.of(ValidationReport.ValidationIssue.error(1, 0, "bad"))
            );
            assertFalse(report.valid());
            assertEquals(1, report.issues().size());
        }
    }
}
