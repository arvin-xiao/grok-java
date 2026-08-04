package ai.grok.tools.file;

import ai.grok.tool.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * File read/write/search-replace tool.
 */
public class FileTool implements Tool<FileTool.Input> {
    private static final Logger log = LoggerFactory.getLogger(FileTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("action")
                .put("type", "string")
                .put("description", "Action: read, write, or search_replace");
        props.putObject("path")
                .put("type", "string")
                .put("description", "File path (relative to working directory)");
        props.putObject("content")
                .put("type", "string")
                .put("description", "Content to write (for write action)");
        props.putObject("old_text")
                .put("type", "string")
                .put("description", "Text to find (for search_replace action)");
        props.putObject("new_text")
                .put("type", "string")
                .put("description", "Replacement text (for search_replace action)");
        props.putObject("start_line")
                .put("type", "integer")
                .put("description", "Start line for partial read (1-based)");
        props.putObject("end_line")
                .put("type", "integer")
                .put("description", "End line for partial read (1-based, inclusive)");

        schema.putArray("required").add("action").add("path");

        return new ToolDefinition(
                new ToolId("file"),
                "File",
                "Read, write, or search-and-replace in files.",
                schema
        );
    }

    @Override
    public CompletableFuture<ToolResult> execute(ToolCallContext context, Input input) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path basePath = Path.of(context.workingDirectory());
                Path filePath = basePath.resolve(input.path).normalize();

                // Security: ensure path is within working directory
                if (!filePath.startsWith(basePath)) {
                    return new ToolResult.Failure(new ToolCallId("file"),
                            "Path traversal not allowed: " + input.path);
                }

                return switch (input.action) {
                    case "read" -> readFile(filePath, input.startLine, input.endLine);
                    case "write" -> writeFile(filePath, input.content);
                    case "search_replace" -> searchReplace(filePath, input.oldText, input.newText);
                    default -> new ToolResult.Failure(new ToolCallId("file"),
                            "Unknown action: " + input.action);
                };
            } catch (Exception e) {
                return new ToolResult.Failure(new ToolCallId("file"), e.getMessage());
            }
        }, Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("file-tool").factory()));
    }

    private ToolResult readFile(Path path, int startLine, int endLine) throws IOException {
        if (!Files.exists(path)) {
            return new ToolResult.Failure(new ToolCallId("file"), "File not found: " + path);
        }
        var lines = Files.readAllLines(path);

        if (startLine > 0 && endLine > 0) {
            int start = Math.max(0, startLine - 1);
            int end = Math.min(lines.size(), endLine);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(String.format("%4d | %s\n", i + 1, lines.get(i)));
            }
            return new ToolResult.Success(new ToolCallId("file"), sb.toString());
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(String.format("%4d | %s\n", i + 1, lines.get(i)));
        }
        return new ToolResult.Success(new ToolCallId("file"), sb.toString());
    }

    private ToolResult writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return new ToolResult.Success(new ToolCallId("file"),
                "Successfully wrote " + Files.size(path) + " bytes to " + path);
    }

    private ToolResult searchReplace(Path path, String oldText, String newText) throws IOException {
        if (!Files.exists(path)) {
            return new ToolResult.Failure(new ToolCallId("file"), "File not found: " + path);
        }
        String content = Files.readString(path);
        int count = countOccurrences(content, oldText);

        if (count == 0) {
            return new ToolResult.Failure(new ToolCallId("file"),
                    "Text not found in file: " + path);
        }
        if (count > 1) {
            return new ToolResult.Failure(new ToolCallId("file"),
                    "Text found " + count + " times. Must be unique for search_replace.");
        }

        String updated = content.replace(oldText, newText);
        Files.writeString(path, updated);
        return new ToolResult.Success(new ToolCallId("file"),
                "Successfully replaced 1 occurrence in " + path);
    }

    private int countOccurrences(String text, String sub) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    @Override
    public Input parseArguments(JsonNode arguments) {
        return new Input(
                arguments.path("action").asText("read"),
                arguments.path("path").asText(""),
                arguments.path("content").asText(null),
                arguments.path("old_text").asText(null),
                arguments.path("new_text").asText(null),
                arguments.path("start_line").asInt(0),
                arguments.path("end_line").asInt(0)
        );
    }

    public record Input(
            String action, String path, String content,
            String oldText, String newText,
            int startLine, int endLine
    ) {
    }
}
