package ai.grok.tools.file;

import ai.grok.tool.api.ToolCallContext;
import ai.grok.tool.api.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for FileTool — executes real file operations.
 */
class FileToolIntegrationTest {

    private final FileTool tool = new FileTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void definitionShouldHaveCorrectName() {
        var def = tool.definition();
        assertNotNull(def.name());
        assertNotNull(def.inputSchema());
    }

    @Test
    void shouldReadFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Hello, World!\nLine 2");

        var ctx = new ToolCallContext(
                tempDir.toString(), "s1",
                new ToolCallContext.ToolCallProgress() {
                    @Override
                    public void onOutput(String chunk) {
                    }
                }
        );

        var input = tool.parseArguments(
                mapper.readTree("{\"action\": \"read\", \"path\": \"" + file.toString().replace("\\", "/") + "\"}")
        );
        var result = tool.execute(ctx, input).get(10, TimeUnit.SECONDS);

        assertInstanceOf(ToolResult.Success.class, result);
        assertTrue(((ToolResult.Success) result).output().contains("Hello, World!"));
    }

    @Test
    void shouldWriteFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("output.txt");

        var ctx = new ToolCallContext(
                tempDir.toString(), "s1",
                new ToolCallContext.ToolCallProgress() {
                    @Override
                    public void onOutput(String chunk) {
                    }
                }
        );

        var input = tool.parseArguments(
                mapper.readTree("{\"action\": \"write\", \"path\": \"" + file.toString().replace("\\", "/") + "\", \"content\": \"Written by test\"}")
        );
        var result = tool.execute(ctx, input).get(10, TimeUnit.SECONDS);

        assertInstanceOf(ToolResult.Success.class, result);
        assertTrue(Files.exists(file));
        assertEquals("Written by test", Files.readString(file));
    }

    @Test
    void shouldReportErrorForMissingRead(@TempDir Path tempDir) throws Exception {
        var ctx = new ToolCallContext(
                tempDir.toString(), "s1",
                new ToolCallContext.ToolCallProgress() {
                    @Override
                    public void onOutput(String chunk) {
                    }
                }
        );

        var input = tool.parseArguments(
                mapper.readTree("{\"action\": \"read\", \"path\": \"" + tempDir.resolve("nonexistent.txt").toString().replace("\\", "/") + "\"}")
        );
        var result = tool.execute(ctx, input).get(10, TimeUnit.SECONDS);

        assertInstanceOf(ToolResult.Failure.class, result);
    }
}
