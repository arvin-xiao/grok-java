package ai.grok.tools.search;

import ai.grok.tool.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Code search tool using regex (grep-like functionality).
 */
public class SearchTool implements Tool<SearchTool.Input> {
    private static final Logger log = LoggerFactory.getLogger(SearchTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("pattern")
                .put("type", "string")
                .put("description", "Regex pattern to search for");
        props.putObject("path")
                .put("type", "string")
                .put("description", "Directory or file to search in (default: working directory)");
        props.putObject("include")
                .put("type", "string")
                .put("description", "Glob pattern for file inclusion (e.g. '*.java')");
        props.putObject("max_results")
                .put("type", "integer")
                .put("description", "Maximum number of results (default: 50)");
        schema.putArray("required").add("pattern");

        return new ToolDefinition(
                new ToolId("search"),
                "Search",
                "Search file contents using regex patterns. Returns matching lines with file paths and line numbers.",
                schema
        );
    }

    @Override
    public CompletableFuture<ToolResult> execute(ToolCallContext context, Input input) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path basePath = Path.of(context.workingDirectory());
                Path searchPath = input.path != null
                        ? basePath.resolve(input.path).normalize()
                        : basePath;
                int maxResults = input.maxResults > 0 ? input.maxResults : 50;

                Pattern pattern = Pattern.compile(input.pattern);
                var results = new ArrayList<String>();
                int[] count = {0};

                if (Files.isRegularFile(searchPath)) {
                    searchFile(searchPath, basePath, pattern, input.include, results, count, maxResults);
                } else if (Files.isDirectory(searchPath)) {
                    Files.walkFileTree(searchPath, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (count[0] >= maxResults) return FileVisitResult.TERMINATE;
                            if (shouldInclude(file, input.include)) {
                                searchFile(file, basePath, pattern, input.include, results, count, maxResults);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            String name = dir.getFileName().toString();
                            if (name.equals(".git") || name.equals("node_modules") || name.equals("target")
                                    || name.equals(".idea") || name.equals(".vscode")) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }

                if (results.isEmpty()) {
                    return new ToolResult.Success(new ToolCallId("search"), "No matches found.");
                }

                return new ToolResult.Success(new ToolCallId("search"),
                        String.join("\n", results) + "\n\n(" + results.size() + " matches)");
            } catch (Exception e) {
                return new ToolResult.Failure(new ToolCallId("search"), e.getMessage());
            }
        }, Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("search-tool").factory()));
    }

    private void searchFile(Path file, Path basePath, Pattern pattern, String include,
                            ArrayList<String> results, int[] count, int maxResults) {
        if (count[0] >= maxResults) return;
        try {
            var lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size() && count[0] < maxResults; i++) {
                if (pattern.matcher(lines.get(i)).find()) {
                    Path rel = basePath.relativize(file);
                    results.add(String.format("%s:%d: %s", rel, i + 1, lines.get(i).trim()));
                    count[0]++;
                }
            }
        } catch (Exception ignored) {
            // Skip binary/unreadable files
        }
    }

    private boolean shouldInclude(Path file, String include) {
        if (include == null || include.isEmpty()) return true;
        String name = file.getFileName().toString();
        // Simple glob matching
        String regex = include.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return name.matches(regex);
    }

    @Override
    public Input parseArguments(JsonNode arguments) {
        return new Input(
                arguments.path("pattern").asText(""),
                arguments.path("path").asText(null),
                arguments.path("include").asText(null),
                arguments.path("max_results").asInt(50)
        );
    }

    public record Input(String pattern, String path, String include, int maxResults) {
    }
}
