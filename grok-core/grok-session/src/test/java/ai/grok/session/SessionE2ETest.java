package ai.grok.session;

import ai.grok.agent.AgentBuilder;
import ai.grok.agent.GrokAgent;
import ai.grok.config.GrokConfig;
import ai.grok.registry.ToolBridge;
import ai.grok.registry.ToolRegistry;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for the Session → Agent → Tool pipeline.
 * Uses a Mock ChatLanguageModel to verify the complete flow from user prompt
 * through session management, agent loop, tool execution, and response delivery.
 */
class SessionE2ETest {

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

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        registry.register(new BashTool());
        registry.register(new FileTool());
        toolBridge = new ToolBridge(registry, tempDir.toString(), "test-session");
        config = GrokConfig.defaults();
    }

    private GrokAgent buildAgent(MockChatModel mock) {
        return new AgentBuilder()
                .name("test-agent")
                .toolBridge(toolBridge)
                .config(config)
                .chatModel(mock)
                .build();
    }

    /**
     * Mock ChatLanguageModel that returns pre-configured responses in sequence.
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
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("Mock: no more responses"))
                    .build();
        }

        @Override
        public Response<AiMessage> generate(List<dev.langchain4j.data.message.ChatMessage> messages) {
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
    @DisplayName("Session prompt flow")
    class SessionPromptFlow {

        @Test
        @DisplayName("simple prompt → agent → text response")
        void simplePromptReturnsResponse() throws Exception {
            var mock = new MockChatModel();
            mock.enqueue(textResponse("Hello! How can I help?"));

            var agent = buildAgent(mock);
            var session = new DefaultSession("s1", agent);

            var result = session.prompt("Hi there").get(10, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals("Hello! How can I help?", result.textContent());
            assertTrue(result.totalTurns() >= 1);
        }

        @Test
        @DisplayName("prompt with tool call → tool execution → final response")
        void promptWithToolExecution() throws Exception {
            var mock = new MockChatModel();
            // LLM first requests a Bash tool call
            mock.enqueue(toolCallResponse("tc-1", "Bash",
                    "{\"command\": \"echo session-test\"}"));
            // Then gives final text after seeing tool result
            mock.enqueue(textResponse("Output was: session-test"));

            var agent = buildAgent(mock);
            var session = new DefaultSession("s2", agent);

            var result = session.prompt("Run echo session-test").get(10, TimeUnit.SECONDS);

            assertEquals("Output was: session-test", result.textContent());
            assertEquals(2, mock.callCount()); // 1 tool call + 1 final
        }

        @Test
        @DisplayName("multiple prompts maintain conversation history")
        void multiplePromptsInSession() throws Exception {
            var mock = new MockChatModel();
            mock.enqueue(textResponse("First response"));
            mock.enqueue(textResponse("Second response"));

            var agent = buildAgent(mock);
            var session = new DefaultSession("s3", agent);

            var r1 = session.prompt("First question").get(10, TimeUnit.SECONDS);
            var r2 = session.prompt("Second question").get(10, TimeUnit.SECONDS);

            assertEquals("First response", r1.textContent());
            assertEquals("Second response", r2.textContent());

            // History should contain: system + user1 + assistant1 + user2 + assistant2
            var history = session.history();
            assertTrue(history.size() >= 5);
            assertInstanceOf(ChatMessage.System.class, history.get(0));
            assertInstanceOf(ChatMessage.User.class, history.get(1));
            assertInstanceOf(ChatMessage.Assistant.class, history.get(2));
        }
    }

    @Nested
    @DisplayName("Session with file operations")
    class SessionFileOps {

        @Test
        @DisplayName("agent writes file via session, then reads it back")
        void writeAndReadFileViaSession() throws Exception {
            var mock = new MockChatModel();
            String filePath = tempDir.resolve("session-file.txt").toString().replace("\\", "/");

            // Step 1: Write file
            mock.enqueue(toolCallResponse("tc-1", "File",
                    "{\"action\": \"write\", \"path\": \"" + filePath + "\", \"content\": \"Session data\"}"));
            // Step 2: Read file
            mock.enqueue(toolCallResponse("tc-2", "File",
                    "{\"action\": \"read\", \"path\": \"" + filePath + "\"}"));
            // Step 3: Summarize
            mock.enqueue(textResponse("File contains: Session data"));

            var agent = buildAgent(mock);
            var session = new DefaultSession("s4", agent);

            var result = session.prompt("Write and read a file").get(10, TimeUnit.SECONDS);

            assertEquals("File contains: Session data", result.textContent());
            assertEquals(3, mock.callCount()); // 2 tool calls + 1 final
        }
    }

    @Nested
    @DisplayName("Session state management")
    class SessionState {

        @Test
        @DisplayName("session starts IDLE and returns to IDLE after prompt")
        void stateTransitions() throws Exception {
            var mock = new MockChatModel();
            mock.enqueue(textResponse("Done"));

            var agent = buildAgent(mock);
            var session = new DefaultSession("s5", agent);

            assertEquals(ai.grok.session.api.Session.SessionState.IDLE, session.state());

            var result = session.prompt("Test").get(10, TimeUnit.SECONDS);
            assertNotNull(result);

            // After completion, should be back to IDLE
            // (might need brief wait for async state update)
            Thread.sleep(100);
            assertEquals(ai.grok.session.api.Session.SessionState.IDLE, session.state());
        }

        @Test
        @DisplayName("session close resets to IDLE")
        void closeResetsState() {
            var mock = new MockChatModel();
            var agent = buildAgent(mock);
            var session = new DefaultSession("s6", agent);

            session.close();
            assertEquals(ai.grok.session.api.Session.SessionState.IDLE, session.state());
        }
    }

    @Nested
    @DisplayName("Session LLM context verification")
    class SessionLLMContext {

        @Test
        @DisplayName("LLM receives full conversation history including system prompt")
        void fullHistorySentToLLM() throws Exception {
            var mock = new MockChatModel();
            mock.enqueue(textResponse("Response 1"));
            mock.enqueue(textResponse("Response 2"));

            var agent = buildAgent(mock);
            var session = new DefaultSession("s7", agent);

            session.prompt("First").get(10, TimeUnit.SECONDS);
            session.prompt("Second").get(10, TimeUnit.SECONDS);

            // Second call should have more messages (accumulated history)
            var requests = mock.receivedRequests();
            assertTrue(requests.size() >= 2);

            var firstReq = requests.get(0);
            var secondReq = requests.get(1);
            // Second request should have more messages than first
            assertTrue(secondReq.messages().size() > firstReq.messages().size());
        }
    }
}
