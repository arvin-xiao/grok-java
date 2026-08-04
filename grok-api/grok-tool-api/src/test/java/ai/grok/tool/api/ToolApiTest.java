package ai.grok.tool.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for grok-tool-api core types.
 */
class ToolApiTest {

    // ─── ToolId ────────────────────────────────────────────────────

    @Nested
    class ToolIdTest {
        @Test
        void shouldCreateValidId() {
            var id = new ToolId("bash");
            assertEquals("bash", id.value());
            assertEquals("bash", id.toString());
        }

        @Test
        void shouldRejectBlankId() {
            assertThrows(IllegalArgumentException.class, () -> new ToolId(null));
            assertThrows(IllegalArgumentException.class, () -> new ToolId(""));
            assertThrows(IllegalArgumentException.class, () -> new ToolId("  "));
        }

        @Test
        void shouldSupportEquality() {
            assertEquals(new ToolId("bash"), new ToolId("bash"));
            assertNotEquals(new ToolId("bash"), new ToolId("file"));
        }
    }

    // ─── ToolCallId ────────────────────────────────────────────────

    @Nested
    class ToolCallIdTest {
        @Test
        void shouldCreateValidCallId() {
            var id = new ToolCallId("call-123");
            assertEquals("call-123", id.value());
        }

        @Test
        void shouldRejectBlankCallId() {
            assertThrows(IllegalArgumentException.class, () -> new ToolCallId(null));
            assertThrows(IllegalArgumentException.class, () -> new ToolCallId(""));
        }
    }

    // ─── ToolResult ────────────────────────────────────────────────

    @Nested
    class ToolResultTest {
        private final ToolCallId callId = new ToolCallId("call-1");

        @Test
        void successShouldHoldOutput() {
            var result = new ToolResult.Success(callId, "hello world");
            assertEquals(callId, result.callId());
            assertEquals("hello world", result.output());
        }

        @Test
        void successShouldDefaultNullOutputToEmpty() {
            var result = new ToolResult.Success(callId, null);
            assertEquals("", result.output());
        }

        @Test
        void failureShouldHoldErrorAndExitCode() {
            var result = new ToolResult.Failure(callId, "not found", 127);
            assertEquals("not found", result.error());
            assertEquals(127, result.exitCode());
        }

        @Test
        void failureTwoArgShouldDefaultExitCodeToOne() {
            var result = new ToolResult.Failure(callId, "error");
            assertEquals(1, result.exitCode());
        }

        @Test
        void needsApprovalShouldHoldReason() {
            var result = new ToolResult.NeedsApproval(callId, "dangerous", "rm -rf /");
            assertEquals("dangerous", result.reason());
            assertEquals("rm -rf /", result.command());
        }

        @Test
        void sealedInterfaceShouldPatternMatch() {
            ToolResult result = new ToolResult.Success(callId, "ok");
            String matched = switch (result) {
                case ToolResult.Success s -> "success:" + s.output();
                case ToolResult.Failure f -> "fail:" + f.error();
                case ToolResult.NeedsApproval n -> "ask:" + n.reason();
            };
            assertEquals("success:ok", matched);
        }
    }

    // ─── ToolOutput ────────────────────────────────────────────────

    @Nested
    class ToolOutputTest {
        @Test
        void textOutputShouldHoldText() {
            var output = ToolOutput.Text.of("hello");
            assertEquals("hello", output.text());
            assertEquals("hello", output.toText());
        }

        @Test
        void textOutputShouldDefaultNullToEmpty() {
            var output = new ToolOutput.Text(null);
            assertEquals("", output.text());
        }

        @Test
        void dynamicOutputShouldHoldJson() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree("{\"key\":\"value\"}");
            var output = ToolOutput.Dynamic.of(node);
            assertEquals(node, output.value());
            assertTrue(output.toText().contains("key"));
        }

        @Test
        void dynamicOutputShouldRejectNull() {
            assertThrows(IllegalArgumentException.class, () -> ToolOutput.Dynamic.of(null));
        }

        @Test
        void mediaGenOutputShouldHoldPath() {
            Path path = Path.of("/tmp/image.png");
            var output = ToolOutput.MediaGen.of(path);
            assertEquals(path, output.path());
            assertEquals("image.png", output.filename());
            assertTrue(output.toText().contains("image.png"));
        }

        @Test
        void mediaGenShouldRejectNullPath() {
            assertThrows(NullPointerException.class, () -> ToolOutput.MediaGen.of(null));
        }

        @Test
        void sealedInterfaceShouldExhaustivelyMatch() {
            ToolOutput output = ToolOutput.Text.of("test");
            String kind = switch (output) {
                case ToolOutput.Text t -> "text";
                case ToolOutput.Dynamic d -> "dynamic";
                case ToolOutput.MediaGen m -> "media";
            };
            assertEquals("text", kind);
        }
    }

    // ─── ToolDefinition ────────────────────────────────────────────

    @Nested
    class ToolDefinitionTest {
        @Test
        void shouldCreateValidDefinition() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode schema = mapper.readTree("{\"type\":\"object\"}");
            var def = new ToolDefinition(new ToolId("bash"), "Bash", "Execute commands", schema);
            assertEquals("Bash", def.name());
            assertEquals("Execute commands", def.description());
        }

        @Test
        void shouldRejectBlankName() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ToolDefinition(new ToolId("x"), "", "desc", null));
        }

        @Test
        void shouldRejectBlankDescription() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ToolDefinition(new ToolId("x"), "name", "", null));
        }
    }

    // ─── ToolCallContext ────────────────────────────────────────────

    @Nested
    class ToolCallContextTest {
        @Test
        void shouldHoldContextFields() {
            var progress = new ToolCallContext.ToolCallProgress() {
                @Override
                public void onOutput(String chunk) {
                }
            };
            var ctx = new ToolCallContext("/home/user", "session-1", progress);
            assertEquals("/home/user", ctx.workingDirectory());
            assertEquals("session-1", ctx.sessionId());
            assertNotNull(ctx.progress());
        }
    }
}
