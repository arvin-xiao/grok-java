package ai.grok.registry;

import ai.grok.tool.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ToolRegistry.
 */
class ToolRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
    }

    @Test
    void registerShouldAddTool() {
        registry.register(stubTool("bash", "Execute commands"));
        assertEquals(1, registry.size());
    }

    @Test
    void findShouldReturnRegisteredTool() {
        registry.register(stubTool("bash", "Execute commands"));
        var found = registry.find("bash");
        assertTrue(found.isPresent());
        assertEquals("bash", found.get().definition().name());
    }

    @Test
    void findShouldReturnEmptyForUnknown() {
        assertTrue(registry.find("nonexistent").isEmpty());
    }

    @Test
    void definitionsShouldReturnSorted() {
        registry.register(stubTool("file", "File operations"));
        registry.register(stubTool("bash", "Shell commands"));
        registry.register(stubTool("search", "Search code"));

        var defs = registry.definitions();
        assertEquals(3, defs.size());
        assertEquals("bash", defs.get(0).name());
        assertEquals("file", defs.get(1).name());
        assertEquals("search", defs.get(2).name());
    }

    @Test
    void registerAllShouldAddMultiple() {
        registry.registerAll(java.util.List.of(
                stubTool("a", "A"),
                stubTool("b", "B")
        ));
        assertEquals(2, registry.size());
    }

    @Test
    void dispatchUnknownToolShouldReturnFailure() throws Exception {
        var call = new ToolCall(new ToolCallId("c1"), "nonexistent", mapper.createObjectNode());
        var ctx = new ToolCallContext("/tmp", "s1", new ToolCallContext.ToolCallProgress() {
            @Override
            public void onOutput(String chunk) {
            }
        });

        var result = registry.dispatch(call, ctx).get();
        assertInstanceOf(ToolResult.Failure.class, result);
        assertTrue(((ToolResult.Failure) result).error().contains("Unknown tool"));
    }

    @Test
    void dispatchShouldExecuteTool() throws Exception {
        registry.register(stubTool("echo", "Echo input"));
        var call = new ToolCall(new ToolCallId("c1"), "echo", mapper.createObjectNode());
        var ctx = new ToolCallContext("/tmp", "s1", new ToolCallContext.ToolCallProgress() {
            @Override
            public void onOutput(String chunk) {
            }
        });

        var result = registry.dispatch(call, ctx).get();
        assertInstanceOf(ToolResult.Success.class, result);
    }

    @Test
    void duplicateRegisterShouldOverwrite() {
        registry.register(stubTool("bash", "Version 1"));
        registry.register(stubTool("bash", "Version 2"));
        assertEquals(1, registry.size());
        assertEquals("Version 2", registry.find("bash").get().definition().description());
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private Tool<Object> stubTool(String name, String description) {
        return new Tool<>() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(new ToolId(name), name, description, mapper.createObjectNode());
            }

            @Override
            public CompletableFuture<ToolResult> execute(ToolCallContext context, Object input) {
                return CompletableFuture.completedFuture(
                        new ToolResult.Success(new ToolCallId(name), "ok")
                );
            }

            @Override
            public Object parseArguments(JsonNode arguments) {
                return new Object();
            }
        };
    }
}
