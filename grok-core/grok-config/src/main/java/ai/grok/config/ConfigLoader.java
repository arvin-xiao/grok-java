package ai.grok.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads and merges configuration from multiple sources.
 */
public class ConfigLoader {
    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * Load configuration by merging all sources (low → high priority).
     */
    public static GrokConfig load(Path grokHome, Map<String, String> cliOverrides) {
        Map<String, Object> merged = new LinkedHashMap<>();

        // Layer 1: defaults
        mergeDefaults(merged);

        // Layer 2: managed config
        loadYamlIfExists(grokHome.resolve("managed.yaml"), merged);

        // Layer 3: user config
        loadYamlIfExists(grokHome.resolve("config.yaml"), merged);

        // Layer 4: environment variables
        mergeEnvironment(merged);

        // Layer 5: CLI overrides
        if (cliOverrides != null) {
            merged.putAll(cliOverrides);
        }

        return buildConfig(merged, grokHome);
    }

    private static void mergeDefaults(Map<String, Object> merged) {
        GrokConfig defaults = GrokConfig.defaults();
        merged.put("model.provider", defaults.model().provider());
        merged.put("model.name", defaults.model().modelName());
        merged.put("model.base_url", defaults.model().baseUrl());
        merged.put("model.temperature", defaults.model().temperature());
        merged.put("model.max_tokens", defaults.model().maxTokens());
        merged.put("max_turns", defaults.maxTurns());
        merged.put("compaction_threshold", defaults.compactionTokenThreshold());
    }

    private static void loadYamlIfExists(Path path, Map<String, Object> target) {
        if (!Files.exists(path)) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> yaml = YAML.readValue(path.toFile(), Map.class);
            flatten("", yaml, target);
            log.debug("Loaded config from {}", path);
        } catch (IOException e) {
            log.warn("Failed to load config from {}: {}", path, e.getMessage());
        }
    }

    private static void mergeEnvironment(Map<String, Object> target) {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey != null) target.put("model.api_key", apiKey);

        String model = System.getenv("GROK_MODEL");
        if (model != null) target.put("model.name", model);

        String baseUrl = System.getenv("OPENAI_BASE_URL");
        if (baseUrl != null) target.put("model.base_url", baseUrl);

        String provider = System.getenv("GROK_PROVIDER");
        if (provider != null) target.put("model.provider", provider);
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> map, Map<String, Object> target) {
        for (var entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map) {
                flatten(key, (Map<String, Object>) entry.getValue(), target);
            } else {
                target.put(key, entry.getValue());
            }
        }
    }

    private static GrokConfig buildConfig(Map<String, Object> merged, Path grokHome) {
        var modelCfg = new GrokConfig.ModelConfig(
                str(merged, "model.provider", "openai"),
                str(merged, "model.name", "gpt-4o"),
                str(merged, "model.api_key", null),
                str(merged, "model.base_url", "https://api.openai.com/v1"),
                dbl(merged, "model.temperature", 0.0),
                num(merged, "model.max_tokens", 4096)
        );

        return new GrokConfig(
                modelCfg,
                System.getProperty("user.dir"),
                grokHome,
                Map.of(),
                bool(merged, "headless", false),
                num(merged, "max_turns", 100),
                num(merged, "compaction_threshold", 150_000L),
                ShellEnvironmentPolicy.defaults(),
                TruncationConfig.defaults(),
                WorkflowConfig.defaults()
        );
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v != null ? v.toString() : def;
    }

    private static double dbl(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) try {
            return Double.parseDouble(s);
        } catch (Exception ignored) {
        }
        return def;
    }

    private static int num(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) try {
            return Integer.parseInt(s);
        } catch (Exception ignored) {
        }
        return def;
    }

    private static long num(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) try {
            return Long.parseLong(s);
        } catch (Exception ignored) {
        }
        return def;
    }

    private static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }
}
