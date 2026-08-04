package ai.grok.tools.bash;

import ai.grok.tool.api.ToolCallContext;
import ai.grok.tool.api.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for BashTool — executes real shell commands.
 */
class BashToolIntegrationTest {

    private final BashTool tool = new BashTool();
    private final ObjectMapper mapper = new ObjectMapper();

    private ToolCallContext context() {
        return new ToolCallContext(
                System.getProperty("java.io.tmpdir"),
                "test-session",
                new ToolCallContext.ToolCallProgress() {
                    @Override
                    public void onOutput(String chunk) {
                    }
                }
        );
    }

    @Test
    void definitionShouldHaveCorrectName() {
        var def = tool.definition();
        assertEquals("Bash", def.name());
        assertNotNull(def.inputSchema());
    }

    @Test
    void shouldExecuteEchoCommand() throws Exception {
        var input = tool.parseArguments(
                mapper.readTree("{\"command\": \"echo hello-world\"}")
        );
        var result = tool.execute(context(), input).get(10, TimeUnit.SECONDS);

        assertInstanceOf(ToolResult.Success.class, result);
        var success = (ToolResult.Success) result;
        assertTrue(success.output().contains("hello-world"));
    }

    @Test
    void shouldReturnFailureForBadCommand() throws Exception {
        var input = tool.parseArguments(
                mapper.readTree("{\"command\": \"nonexistent-command-xyz-123\"}")
        );
        var result = tool.execute(context(), input).get(10, TimeUnit.SECONDS);

        // On Windows cmd returns error, on bash returns 127
        assertInstanceOf(ToolResult.Failure.class, result);
    }

    @Test
    void shouldParseInputDefaults() throws Exception {
        var input = tool.parseArguments(mapper.readTree("{\"command\": \"echo test\"}"));
        assertEquals("echo test", input.command());
        assertEquals(120, input.timeout());
    }

    @Test
    void shouldParseCustomTimeout() throws Exception {
        var input = tool.parseArguments(
                mapper.readTree("{\"command\": \"sleep 1\", \"timeout\": 5}")
        );
        assertEquals("sleep 1", input.command());
        assertEquals(5, input.timeout());
    }
}
