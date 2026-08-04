package ai.grok.app;

import ai.grok.agent.AgentBuilder;
import ai.grok.agent.GrokAgent;
import ai.grok.config.ConfigLoader;
import ai.grok.config.GrokConfig;
import ai.grok.headless.HeadlessMode;
import ai.grok.registry.ToolBridge;
import ai.grok.registry.ToolRegistry;
import ai.grok.session.DefaultSession;
import ai.grok.session.api.Session;
import ai.grok.tools.bash.BashTool;
import ai.grok.tools.file.FileTool;
import ai.grok.tools.search.SearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Main application entry point. Integrates Spring Boot with picocli CLI.
 * Can be run as a fat JAR or compiled to native image via GraalVM.
 */
@SpringBootApplication
public class GrokApplication {
    private static final Logger log = LoggerFactory.getLogger(GrokApplication.class);

    public static void main(String[] args) {
        // Parse CLI args before Spring Boot starts
        CliArgs cliArgs = CliArgs.parse(args);

        if (cliArgs.showHelp()) {
            CliArgs.printHelp();
            return;
        }

        if (cliArgs.showVersion()) {
            System.out.println("grok-java 0.1.0-SNAPSHOT");
            return;
        }

        // Store CLI args for Spring context
        System.setProperty("grok.cli.working-dir", cliArgs.workingDirectory());
        System.setProperty("grok.cli.headless", String.valueOf(cliArgs.headless()));
        System.setProperty("grok.cli.prompt", cliArgs.prompt() != null ? cliArgs.prompt() : "");

        SpringApplication app = new SpringApplication(GrokApplication.class);
        app.setHeadless(true);
        app.run(args);
    }

    @Bean
    public GrokConfig grokConfig() {
        Path grokHome = Path.of(System.getProperty("user.home"), ".grok");
        String workDir = System.getProperty("grok.cli.working-dir", System.getProperty("user.dir"));

        Map<String, String> cliOverrides = Map.of();
        GrokConfig config = ConfigLoader.load(grokHome, cliOverrides);

        // Override working directory from CLI
        return new GrokConfig(
                config.model(),
                workDir,
                config.grokHome(),
                config.toolConfig(),
                config.headless(),
                config.maxTurns(),
                config.compactionTokenThreshold(),
                config.shellEnvPolicy(),
                config.truncation(),
                config.workflow()
        );
    }

    @Bean
    public ToolRegistry toolRegistry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BashTool());
        registry.register(new FileTool());
        registry.register(new SearchTool());
        log.info("Registered {} tools", registry.size());
        return registry;
    }

    @Bean
    public ToolBridge toolBridge(ToolRegistry registry, GrokConfig config) {
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        return new ToolBridge(registry, config.workingDirectory(), sessionId);
    }

    @Bean
    public GrokAgent grokAgent(ToolBridge toolBridge, GrokConfig config) {
        return new AgentBuilder()
                .name("grok")
                .toolBridge(toolBridge)
                .config(config)
                .build();
    }

    @Bean
    public Session session(GrokAgent agent) {
        String sessionId = "session-" + UUID.randomUUID().toString().substring(0, 8);
        return new DefaultSession(sessionId, agent);
    }

    @Bean
    public CommandLineRunner runner(Session session, GrokConfig config) {
        return args -> {
            boolean headless = Boolean.parseBoolean(
                    System.getProperty("grok.cli.headless", "true"));
            String prompt = System.getProperty("grok.cli.prompt", "");

            HeadlessMode headlessMode = new HeadlessMode(session);

            if (!prompt.isEmpty()) {
                // Non-interactive: run single prompt and exit
                headlessMode.runOnce(prompt);
            } else {
                // Interactive REPL
                headlessMode.run();
            }
        };
    }
}
