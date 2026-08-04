package ai.grok.agent;

import ai.grok.config.GrokConfig;
import ai.grok.config.GrokConfig.ModelConfig;
import ai.grok.registry.ToolBridge;
import ai.grok.registry.ToolRegistry;
import ai.grok.session.api.AgentRequest;
import ai.grok.session.api.ChatMessage;
import ai.grok.tools.bash.BashTool;
import ai.grok.tools.file.FileTool;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real model integration tests — requires a valid API key.
 * These tests are SKIPPED unless OPENAI_API_KEY is set.
 * <p>
 * Default: DeepSeek V4 Pro (https://api.deepseek.com)
 * <p>
 * Configure via environment variables:
 * OPENAI_API_KEY=your-deepseek-api-key
 * OPENAI_BASE_URL=https://api.deepseek.com   (default)
 * GROK_MODEL=deepseek-v4-pro                (default)
 * <p>
 * Run with:
 * $env:OPENAI_API_KEY="sk-xxx"; mvn test "-Dtest=RealModelE2ETest"
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+",
        disabledReason = "OPENAI_API_KEY not set — skipping real model tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealModelE2ETest {

    @TempDir
    Path tempDir;
    private ToolRegistry registry;
    private ToolBridge toolBridge;
    private GrokConfig config;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        registry.register(new BashTool());
        registry.register(new FileTool());
        toolBridge = new ToolBridge(registry, tempDir.toString(), "real-test-session");

        // Build config — defaults to DeepSeek V4 Pro
        String baseUrl = System.getenv().getOrDefault("OPENAI_BASE_URL", "https://api.deepseek.com");
        String model = System.getenv().getOrDefault("GROK_MODEL", "deepseek-v4-pro");
        String apiKey = System.getenv("OPENAI_API_KEY");

        config = new GrokConfig(
                new ModelConfig("openai", model, apiKey, baseUrl, 0.0, 2048),
                tempDir.toString(),
                Path.of(System.getProperty("user.home"), ".grok"),
                java.util.Map.of(), false, 10, 150_000,
                ai.grok.config.ShellEnvironmentPolicy.defaults(),
                ai.grok.config.TruncationConfig.defaults(),
                ai.grok.config.WorkflowConfig.defaults()
        );
    }

    private GrokAgent buildRealAgent() {
        return new AgentBuilder()
                .name("real-test-agent")
                .toolBridge(toolBridge)
                .config(config)
                .build();
    }

    private AgentRequest userRequest(String content) {
        return new AgentRequest("real-session",
                List.of(new ChatMessage.User(content)));
    }

    // ─── Tests ───────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Real model: simple greeting")
    void simpleGreeting() {
        var agent = buildRealAgent();
        var response = agent.turn(userRequest("Say hello in one sentence."));

        assertNotNull(response.textContent());
        assertFalse(response.textContent().isEmpty());
        System.out.println("[Real Model Response]: " + response.textContent());
    }

    @Test
    @Order(2)
    @DisplayName("Real model: execute Bash tool")
    void executeBashTool() {
        var agent = buildRealAgent();
        var response = agent.turn(userRequest(
                "Run the command 'echo Hello from DeepSeek' using the Bash tool, then tell me the output."));

        assertNotNull(response.textContent());
        assertFalse(response.textContent().isEmpty());
        System.out.println("[Real Model + Bash]: " + response.textContent());
    }

    @Test
    @Order(3)
    @DisplayName("Real model: write and read a file")
    void fileWriteAndRead() {
        var agent = buildRealAgent();
        String filePath = tempDir.resolve("real-test.txt").toString().replace("\\", "/");

        var response = agent.turn(userRequest(
                "Write 'Hello from real model test' to " + filePath +
                        " using the File tool, then read it back and confirm the content."));

        assertNotNull(response.textContent());
        System.out.println("[Real Model + File]: " + response.textContent());
    }

    @Test
    @Order(4)
    @DisplayName("Real model: multi-turn conversation")
    void multiTurnConversation() {
        var agent = buildRealAgent();

        // First turn
        var r1 = agent.turn(userRequest("My name is GrokTester. Remember it."));
        System.out.println("[Turn 1]: " + r1.textContent());

        // Second turn — should remember context
        var r2 = agent.turn(new AgentRequest("real-session",
                List.of(
                        new ChatMessage.User("My name is GrokTester. Remember it."),
                        new ChatMessage.Assistant(r1.textContent()),
                        new ChatMessage.User("What is my name?")
                )));

        System.out.println("[Turn 2]: " + r2.textContent());
        assertNotNull(r2.textContent());
        // The model should recall the name
        assertTrue(r2.textContent().toLowerCase().contains("groktester"),
                "Model should recall the name 'GrokTester' but got: " + r2.textContent());
    }

    @Test
    @Order(5)
    @DisplayName("Real model: code generation")
    void codeGeneration() {
        var agent = buildRealAgent();
        var response = agent.turn(userRequest(
                "Write a Java method that calculates fibonacci(10) and returns the result. " +
                        "Just show the code, no explanation needed."));

        assertNotNull(response.textContent());
        System.out.println("[Real Model Code]:\n" + response.textContent());
    }
}
