package ai.grok.agent;

import ai.grok.config.GrokConfig;
import ai.grok.config.GrokConfig.ModelConfig;
import ai.grok.registry.ToolBridge;
import ai.grok.registry.ToolRegistry;
import ai.grok.session.api.AgentEventSink;
import ai.grok.session.api.AgentRequest;
import ai.grok.session.api.AgentResponse;
import ai.grok.session.api.ChatMessage;
import ai.grok.tools.bash.BashTool;
import ai.grok.tools.file.FileTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for the Agent system.
 * Uses a Mock ChatLanguageModel to test the full agent loop
 * (tool registration → agent turn → tool execution → result handling)
 * without requiring a real LLM API key.
 */
class AgentE2ETest {

    @TempDir
    Path tempDir;
    private ToolRegistry registry;
    private ToolBridge toolBridge;
    private GrokConfig config;

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .build();
    }

    // ─── Mock LLM ───────────────────────────────────────────────

    private static ChatResponse toolCallResponse(String callId, String toolName, String arguments) {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id(callId)
                .name(toolName)
                .arguments(arguments)
                .build();
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(req)))
                .build();
    }

    // ─── Helper methods ──────────────────────────────────────────

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        registry.register(new BashTool());
        registry.register(new FileTool());
        toolBridge = new ToolBridge(registry, tempDir.toString(), "test-session");
        config = GrokConfig.defaults();
    }

    private GrokAgent buildAgent(MockChatModel mockModel) {
        return new AgentBuilder()
                .name("test-agent")
                .toolBridge(toolBridge)
                .config(config)
                .chatModel(mockModel)
                .build();
    }

    private GrokAgent buildAgent(MockChatModel mockModel, GrokConfig customConfig) {
        return new AgentBuilder()
                .name("test-agent")
                .toolBridge(toolBridge)
                .config(customConfig)
                .chatModel(mockModel)
                .build();
    }

    private GrokConfig configWithWorkDir(String workDir) {
        return new GrokConfig(
                ModelConfig.defaults(), workDir,
                config.grokHome(), config.toolConfig(), config.headless(),
                config.maxTurns(), config.compactionTokenThreshold(),
                config.shellEnvPolicy(), config.truncation(), config.workflow()
        );
    }

    private GrokConfig configWithMaxTurns(int maxTurns) {
        return new GrokConfig(
                ModelConfig.defaults(), tempDir.toString(),
                config.grokHome(), config.toolConfig(), config.headless(),
                maxTurns, config.compactionTokenThreshold(),
                config.shellEnvPolicy(), config.truncation(), config.workflow()
        );
    }

    private AgentRequest userRequest(String content) {
        return new AgentRequest("test-session",
                List.of(new ChatMessage.User(content)));
    }

    /**
     * A mock ChatLanguageModel that returns pre-configured responses.
     * Supports a sequence of responses (first call → first response, etc.)
     */
    static class MockChatModel implements ChatLanguageModel {
        private final List<ChatResponse> responses = new ArrayList<>();
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final List<ChatRequest> receivedRequests = new ArrayList<>();

        void enqueue(ChatResponse response) {
            responses.add(response);
        }

        int callCount() {
            return callCount.get();
        }

        List<ChatRequest> receivedRequests() {
            return List.copyOf(receivedRequests);
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            receivedRequests.add(request);
            int idx = callCount.getAndIncrement();
            if (idx < responses.size()) {
                return responses.get(idx);
            }
            // Default fallback: return a simple text response
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("Mock: no more responses"))
                    .build();
        }

        @Override
        public Response<AiMessage> generate(List<dev.langchain4j.data.message.ChatMessage> messages) {
            // Core implementation: track calls and return pre-configured AiMessage
            int idx = callCount.getAndIncrement();
            AiMessage aiMsg;
            if (idx < responses.size()) {
                aiMsg = responses.get(idx).aiMessage();
            } else {
                aiMsg = AiMessage.from("Mock: no more responses");
            }
            return Response.from(aiMsg);
        }
    }

    // ─── Tests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Agent basic behavior")
    class AgentBasics {

        @Test
        @DisplayName("should return text response when LLM does not call tools")
        void textOnlyResponse() {
            var mock = new MockChatModel();
            mock.enqueue(textResponse("Hello! I'm the test agent."));

            var agent = buildAgent(mock);
            var response = agent.turn(userRequest("Hi"));

            assertEquals("Hello! I'm the test agent.", response.textContent());
            assertEquals(1, mock.callCount());
        }

        @Test
        @DisplayName("should expose tool definitions from registry")
        void toolDefinitions() {
            var mock = new MockChatModel();
            var agent = buildAgent(mock);

            var defs = agent.toolDefinitions();
            assertEquals(2, defs.size());
            // Definitions are sorted by name
            assertEquals("Bash", defs.get(0).name());
            assertEquals("File", defs.get(1).name());
        }

        @Test
        @DisplayName("should have correct agent metadata")
        void agentMetadata() {
            var mock = new MockChatModel();
            var customConfig = configWithWorkDir(tempDir.toString());
            var agent = buildAgent(mock, customConfig);

            assertEquals("test-agent", agent.name());
            assertNotNull(agent.id());
            assertNotNull(agent.systemPrompt());
            assertTrue(agent.systemPrompt().contains(tempDir.toString()));
        }
    }

    @Nested
    @DisplayName("Agent tool execution loop")
    class AgentToolLoop {

        @Test
        @DisplayName("should execute Bash tool and return final response")
        void bashToolExecution() {
            var mock = new MockChatModel();
            // First: LLM requests a Bash tool call
            mock.enqueue(toolCallResponse("call-1", "Bash",
                    "{\"command\": \"echo hello-from-agent\"}"));
            // Second: After tool result, LLM gives final text
            mock.enqueue(textResponse("The command output: hello-from-agent"));

            var agent = buildAgent(mock);
            var response = agent.turn(userRequest("Run echo hello-from-agent"));

            // Should have called LLM twice (once for tool call, once for final response)
            assertEquals(2, mock.callCount());
            // Final response should be the text
            assertEquals("The command output: hello-from-agent", response.textContent());
        }

        @Test
        @DisplayName("should execute File write then read via tools")
        void fileWriteThenRead() {
            var mock = new MockChatModel();

            // First: LLM writes a file
            mock.enqueue(toolCallResponse("call-1", "File",
                    "{\"action\": \"write\", \"path\": \"" +
                            tempDir.toString().replace("\\", "/") +
                            "/agent-test.txt\", \"content\": \"Written by agent\"}"));
            // Second: LLM reads the file back
            mock.enqueue(toolCallResponse("call-2", "File",
                    "{\"action\": \"read\", \"path\": \"" +
                            tempDir.toString().replace("\\", "/") +
                            "/agent-test.txt\"}"));
            // Third: LLM summarizes
            mock.enqueue(textResponse("File contains: Written by agent"));

            var agent = buildAgent(mock);
            var response = agent.turn(userRequest("Write and read a file"));

            assertEquals(3, mock.callCount());
            assertEquals("File contains: Written by agent", response.textContent());
        }

        @Test
        @DisplayName("should handle tool execution failure gracefully")
        void toolFailureHandled() {
            var mock = new MockChatModel();
            // Request a non-existent file (will fail)
            mock.enqueue(toolCallResponse("call-1", "File",
                    "{\"action\": \"read\", \"path\": \"/nonexistent/path/missing.txt\"}"));
            // LLM responds after seeing the error
            mock.enqueue(textResponse("The file does not exist."));

            var agent = buildAgent(mock);
            var response = agent.turn(userRequest("Read a missing file"));

            assertEquals(2, mock.callCount());
            assertEquals("The file does not exist.", response.textContent());
        }

        @Test
        @DisplayName("should handle unknown tool name")
        void unknownToolName() {
            var mock = new MockChatModel();
            mock.enqueue(toolCallResponse("call-1", "NonExistentTool", "{}"));
            mock.enqueue(textResponse("That tool is not available."));

            var agent = buildAgent(mock);
            var response = agent.turn(userRequest("Use a fake tool"));

            assertEquals(2, mock.callCount());
            assertEquals("That tool is not available.", response.textContent());
        }
    }

    @Nested
    @DisplayName("Agent max turns and cancellation")
    class AgentControl {

        @Test
        @DisplayName("should stop after max turns")
        void maxTurnsLimit() {
            var mock = new MockChatModel();
            // Always request tool calls — never give a text response
            for (int i = 0; i < 20; i++) {
                mock.enqueue(toolCallResponse("call-" + i, "Bash",
                        "{\"command\": \"echo loop\"}"));
            }

            // Set maxTurns=3 so the loop hits the limit quickly
            var limitedConfig = configWithMaxTurns(3);
            var agent = buildAgent(mock, limitedConfig);
            var response = agent.turn(userRequest("Loop forever"));

            // Should stop at max turns with the warning message
            assertEquals("[max turns reached]", response.textContent());
        }

        @Test
        @DisplayName("should support cancellation during execution")
        void cancellation() throws Exception {
            var mock = new MockChatModel();
            // Enqueue enough tool calls to keep the loop running
            for (int i = 0; i < 50; i++) {
                mock.enqueue(toolCallResponse("call-" + i, "Bash",
                        "{\"command\": \"echo loop\"}"));
            }

            var agent = buildAgent(mock);

            // Start the turn in a virtual thread, then cancel from main thread
            var resultRef = new AtomicReference<AgentResponse>();
            var turnThread = Thread.ofVirtual().start(() -> {
                resultRef.set(agent.turn(userRequest("Loop")));
            });

            // Give it a moment to start, then cancel
            Thread.sleep(50);
            agent.cancel();
            turnThread.join(5000);

            // Should have stopped (either max turns or cancelled)
            assertNotNull(resultRef.get());
        }
    }

    @Nested
    @DisplayName("Streaming support")
    class AgentStreaming {

        @Test
        @DisplayName("should stream text response via sink")
        void streamTextResponse() {
            var mock = new MockChatModel();
            mock.enqueue(textResponse("Streamed response content"));

            var agent = buildAgent(mock);
            var textRef = new AtomicReference<>("");
            var completeRef = new AtomicReference<>(false);
            var errorRef = new AtomicReference<Throwable>(null);

            agent.streamTurn(userRequest("Hello"), new AgentEventSink() {
                @Override
                public void onTextDelta(String delta) {
                    textRef.set(textRef.get() + delta);
                }

                @Override
                public void onToolCallStart(String callId, String toolName) {
                }

                @Override
                public void onToolOutput(String callId, String chunk) {
                }

                @Override
                public void onToolCallEnd(String callId, String result) {
                }

                @Override
                public void onTurnComplete() {
                    completeRef.set(true);
                }

                @Override
                public void onError(Throwable error) {
                    errorRef.set(error);
                }
            });

            assertEquals("Streamed response content", textRef.get());
            assertTrue(completeRef.get());
            assertNull(errorRef.get());
        }
    }

    @Nested
    @DisplayName("LLM receives correct context")
    class LLMContext {

        @Test
        @DisplayName("LLM should receive user message in request")
        void userMessageForwarded() {
            var mock = new MockChatModel();
            mock.enqueue(textResponse("Got it"));

            var agent = buildAgent(mock);
            agent.turn(userRequest("Please help me with Java"));

            assertEquals(1, mock.receivedRequests().size());
            var request = mock.receivedRequests().get(0);
            // The messages should contain the user message
            assertFalse(request.messages().isEmpty());
        }

        @Test
        @DisplayName("LLM second turn should include tool results")
        void toolResultsForwarded() {
            var mock = new MockChatModel();
            mock.enqueue(toolCallResponse("call-1", "Bash",
                    "{\"command\": \"echo verify\"}"));
            mock.enqueue(textResponse("Done"));

            var agent = buildAgent(mock);
            agent.turn(userRequest("Run echo verify"));

            // Second request should contain tool execution results
            assertEquals(2, mock.receivedRequests().size());
            var secondRequest = mock.receivedRequests().get(1);
            // Should have more messages than the first (system + user + ai + tool result)
            assertTrue(secondRequest.messages().size() > 1);
        }
    }
}
