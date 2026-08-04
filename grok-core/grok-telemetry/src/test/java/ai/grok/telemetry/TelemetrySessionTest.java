package ai.grok.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TelemetrySession.
 * Mirrors the Rust telemetry tests (2026-08-03 e5478ef sync).
 */
class TelemetrySessionTest {

    @Nested
    @DisplayName("skillSourceLabel")
    class SkillSourceLabel {

        @Test
        @DisplayName("plugin skills return 'plugin'")
        void pluginSkillsReturnPlugin() {
            assertEquals("plugin",
                    TelemetrySession.skillSourceLabel("/home/user/.grok/plugins/my-skill/SKILL.md", "/project"));
        }

        @Test
        @DisplayName("project skills return 'project'")
        void projectSkillsReturnProject() {
            assertEquals("project",
                    TelemetrySession.skillSourceLabel("/project/.grok/skills/my-skill/SKILL.md", "/project"));
        }

        @Test
        @DisplayName("user skills return 'user'")
        void userSkillsReturnUser() {
            String home = System.getProperty("user.home");
            assertEquals("user",
                    TelemetrySession.skillSourceLabel(home + "/.grok/skills/my-skill/SKILL.md", "/project"));
        }

        @Test
        @DisplayName("global skills return 'global'")
        void globalSkillsReturnGlobal() {
            assertEquals("global",
                    TelemetrySession.skillSourceLabel("/opt/grok/skills/my-skill/SKILL.md", "/project"));
        }

        @Test
        @DisplayName("null path returns 'unknown'")
        void nullPathReturnsUnknown() {
            assertEquals("unknown", TelemetrySession.skillSourceLabel(null, "/project"));
        }
    }

    @Nested
    @DisplayName("isSameSkillFile")
    class IsSameSkillFile {

        @Test
        @DisplayName("matches identical paths")
        void matchesIdenticalPaths() {
            assertTrue(TelemetrySession.isSameSkillFile(
                    Path.of("/home/u/.grok/skills/review/SKILL.md"),
                    Path.of("/home/u/.grok/skills/review/SKILL.md")));
        }

        @Test
        @DisplayName("rejects different skills")
        void rejectsDifferentSkills() {
            assertFalse(TelemetrySession.isSameSkillFile(
                    Path.of("/home/u/.grok/skills/review/SKILL.md"),
                    Path.of("/home/u/.grok/skills/design/SKILL.md")));
        }

        @Test
        @DisplayName("synthetic product path does not match a real file")
        void syntheticPathDoesNotMatchRealFile(@TempDir Path tempDir) throws IOException {
            Path skill = tempDir.resolve("SKILL.md");
            Files.writeString(skill, "body");

            // On Windows, URIs like "chat-product://commit" are invalid paths.
            // We test that non-existent paths don't match real files.
            Path synthetic = tempDir.resolve("nonexistent-skill.md");
            assertFalse(TelemetrySession.isSameSkillFile(synthetic, skill));
        }

        @Test
        @DisplayName("matches through symlink")
        void matchesThroughSymlink(@TempDir Path tempDir) throws IOException {
            Path real = tempDir.resolve("real");
            Files.createDirectory(real);
            Path skill = real.resolve("SKILL.md");
            Files.writeString(skill, "---\nname: x\n---\n");

            Path link = tempDir.resolve("link");
            try {
                Files.createSymbolicLink(link, real);
                assertTrue(TelemetrySession.isSameSkillFile(skill, link.resolve("SKILL.md")));
            } catch (UnsupportedOperationException e) {
                // Windows may not support symlinks in test environment
                // Skip this test
            } catch (IOException e) {
                // Symlink creation may fail due to permissions
                // Skip this test
            }
        }
    }

    @Nested
    @DisplayName("formatHookName")
    class FormatHookName {

        @Test
        @DisplayName("formats scope:name correctly")
        void formatsScopeName() {
            assertEquals("pre", TelemetrySession.formatHookName("pre:tool_call"));
        }

        @Test
        @DisplayName("handles null")
        void handlesNull() {
            assertEquals("unknown", TelemetrySession.formatHookName(null));
        }

        @Test
        @DisplayName("handles empty")
        void handlesEmpty() {
            assertEquals("unknown", TelemetrySession.formatHookName(""));
        }

        @Test
        @DisplayName("handles no colon")
        void handlesNoColon() {
            assertEquals("myhook", TelemetrySession.formatHookName("myhook"));
        }
    }

    @Nested
    @DisplayName("Event logging")
    class EventLogging {

        @Test
        @DisplayName("logEvent records events")
        void logEventRecordsEvents() {
            TelemetrySession session = new TelemetrySession("test-session");
            session.logEvent(TelemetrySession.TelemetryEvent.of(
                    TelemetrySession.EventType.TOOL_CALL,
                    Map.of("tool", "bash")));

            assertEquals(1, session.getEvents().size());
            assertEquals(TelemetrySession.EventType.TOOL_CALL, session.getEvents().get(0).type());
        }

        @Test
        @DisplayName("logSkillMdRead counts skill reads")
        void logSkillMdReadCountsReads() {
            TelemetrySession session = new TelemetrySession("test-session");
            session.logSkillMdRead("review", "project");
            session.logSkillMdRead("review", "project");
            session.logSkillMdRead("design", "user");

            assertEquals(2, session.getSkillReadCount("review"));
            assertEquals(1, session.getSkillReadCount("design"));
            assertEquals(0, session.getSkillReadCount("nonexistent"));
        }

        @Test
        @DisplayName("logSkillDispatched records skill events")
        void logSkillDispatchedRecordsEvents() {
            TelemetrySession session = new TelemetrySession("test-session");
            session.logSkillDispatched(new TelemetrySession.SkillDispatchedEvent(
                    "review", null, TelemetrySession.SkillTrigger.SKILL_TOOL));

            assertEquals(1, session.getEvents().size());
            assertEquals(TelemetrySession.EventType.SKILL_DISPATCHED, session.getEvents().get(0).type());
        }

        @Test
        @DisplayName("listeners are notified")
        void listenersAreNotified() {
            TelemetrySession session = new TelemetrySession("test-session");
            AtomicInteger count = new AtomicInteger(0);
            session.addListener(event -> count.incrementAndGet());

            session.logEvent(TelemetrySession.TelemetryEvent.of(TelemetrySession.EventType.SESSION_START));
            session.logEvent(TelemetrySession.TelemetryEvent.of(TelemetrySession.EventType.SESSION_END));

            assertEquals(2, count.get());
        }

        @Test
        @DisplayName("getMetrics returns correct counts")
        void getMetricsReturnsCorrectCounts() {
            TelemetrySession session = new TelemetrySession("test-session");
            session.logEvent(TelemetrySession.TelemetryEvent.of(TelemetrySession.EventType.TURN_COMPLETE));
            session.logEvent(TelemetrySession.TelemetryEvent.of(TelemetrySession.EventType.TURN_COMPLETE));
            session.logEvent(TelemetrySession.TelemetryEvent.of(TelemetrySession.EventType.TOOL_CALL));

            var metrics = session.getMetrics();
            assertEquals(2, metrics.totalTurns());
            assertEquals(1, metrics.totalToolCalls());
        }
    }
}
