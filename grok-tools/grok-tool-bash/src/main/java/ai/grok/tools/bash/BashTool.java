package ai.grok.tools.bash;

import ai.grok.tool.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Bash/Shell command execution tool. Runs commands in the working directory.
 */
public class BashTool implements Tool<BashTool.Input> {
    private static final Logger log = LoggerFactory.getLogger(BashTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("command")
                .put("type", "string")
                .put("description", "The shell command to execute");
        props.putObject("timeout")
                .put("type", "integer")
                .put("description", "Timeout in seconds (default: 120)");
        schema.putArray("required").add("command");

        return new ToolDefinition(
                new ToolId("bash"),
                "Bash",
                "Execute a shell command in the working directory. Returns stdout and stderr.",
                schema
        );
    }

    @Override
    public CompletableFuture<ToolResult> execute(ToolCallContext context, Input input) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String cwd = context.workingDirectory();
                int timeout = input.timeout > 0 ? input.timeout : 120;

                log.info("Executing: {} (cwd: {}, timeout: {}s)", input.command, cwd, timeout);

                // Determine shell based on OS
                String os = System.getProperty("os.name", "").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("win")) {
                    pb = new ProcessBuilder("cmd", "/c", input.command);
                } else {
                    pb = new ProcessBuilder("bash", "-c", input.command);
                }
                pb.directory(new File(cwd));
                pb.redirectErrorStream(true);

                Process process = pb.start();
                StringBuilder output = new StringBuilder();

                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                        context.progress().onOutput(line);
                    }
                }

                boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return new ToolResult.Failure(
                            new ToolCallId("bash"),
                            "Command timed out after " + timeout + "s",
                            124
                    );
                }

                int exitCode = process.exitValue();
                String result = output.toString().trim();

                if (exitCode == 0) {
                    return new ToolResult.Success(new ToolCallId("bash"), result);
                } else {
                    return new ToolResult.Failure(
                            new ToolCallId("bash"),
                            result.isEmpty() ? "Command failed with exit code " + exitCode : result,
                            exitCode
                    );
                }
            } catch (Exception e) {
                return new ToolResult.Failure(new ToolCallId("bash"), "Execution error: " + e.getMessage());
            }
        }, Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("bash-tool").factory()));
    }

    @Override
    public Input parseArguments(JsonNode arguments) {
        return new Input(
                arguments.path("command").asText(""),
                arguments.path("timeout").asInt(120)
        );
    }

    public record Input(String command, int timeout) {
    }
}
