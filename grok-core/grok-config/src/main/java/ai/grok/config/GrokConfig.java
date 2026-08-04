package ai.grok.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Grok configuration. Supports 6-layer merging (low to high priority):
 * 1. System defaults (/etc/grok)
 * 2. Managed config ($GROK_HOME/managed.yaml)
 * 3. User config ($GROK_HOME/config.yaml)
 * 4. Requirements (cloud-signed)
 * 5. Environment variables
 * 6. CLI overrides
 */
public record GrokConfig(
        ModelConfig model,
        String workingDirectory,
        Path grokHome,
        Map<String, Object> toolConfig,
        boolean headless,
        int maxTurns,
        long compactionTokenThreshold,
        ShellEnvironmentPolicy shellEnvPolicy,
        TruncationConfig truncation,
        WorkflowConfig workflow
) {
    public static GrokConfig defaults() {
        return new GrokConfig(
                ModelConfig.defaults(),
                System.getProperty("user.dir"),
                Path.of(System.getProperty("user.home"), ".grok"),
                Map.of(),
                false,
                100,
                150_000,
                ShellEnvironmentPolicy.defaults(),
                TruncationConfig.defaults(),
                WorkflowConfig.defaults()
        );
    }

    public record ModelConfig(
            String provider,
            String modelName,
            String apiKey,
            String baseUrl,
            double temperature,
            int maxTokens
    ) {
        public static ModelConfig defaults() {
            return new ModelConfig(
                    "openai",
                    Optional.ofNullable(System.getenv("GROK_MODEL")).orElse("gpt-4o"),
                    System.getenv("OPENAI_API_KEY"),
                    Optional.ofNullable(System.getenv("OPENAI_BASE_URL")).orElse("https://api.openai.com/v1"),
                    0.0,
                    4096
            );
        }
    }
}
