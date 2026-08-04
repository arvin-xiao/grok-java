package ai.grok.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for grok-config module.
 */
class ConfigTest {

    // ─── ShellEnvironmentPolicy ────────────────────────────────────

    @Nested
    class ShellEnvironmentPolicyTest {

        @Test
        void defaultsShouldBeNoop() {
            var policy = ShellEnvironmentPolicy.defaults();
            assertTrue(policy.isNoop());
            assertEquals(ShellEnvironmentPolicy.InheritMode.ALL, policy.inherit());
            assertTrue(policy.ignoreDefaultExcludes());
        }

        @Test
        void inheritAllShouldPassThroughEverything() {
            var policy = new ShellEnvironmentPolicy(
                    ShellEnvironmentPolicy.InheritMode.ALL, true,
                    List.of(), Map.of(), List.of()
            );
            Map<String, String> base = Map.of("PATH", "/usr/bin", "HOME", "/home/user", "CUSTOM", "val");
            Map<String, String> result = policy.buildEnvironment(base);
            assertEquals(3, result.size());
            assertEquals("/usr/bin", result.get("PATH"));
            assertEquals("val", result.get("CUSTOM"));
        }

        @Test
        void inheritCoreShouldFilterToCoreVars() {
            var policy = new ShellEnvironmentPolicy(
                    ShellEnvironmentPolicy.InheritMode.CORE, true,
                    List.of(), Map.of(), List.of()
            );
            Map<String, String> base = Map.of(
                    "PATH", "/usr/bin", "HOME", "/home/user",
                    "CUSTOM_VAR", "val", "ANOTHER", "x"
            );
            Map<String, String> result = policy.buildEnvironment(base);
            assertTrue(result.containsKey("PATH"));
            assertTrue(result.containsKey("HOME"));
            assertFalse(result.containsKey("CUSTOM_VAR"));
            assertFalse(result.containsKey("ANOTHER"));
        }

        @Test
        void inheritNoneShouldStartEmpty() {
            var policy = new ShellEnvironmentPolicy(
                    ShellEnvironmentPolicy.InheritMode.NONE, true,
                    List.of(), Map.of(), List.of()
            );
            Map<String, String> base = Map.of("PATH", "/usr/bin", "HOME", "/home/user");
            Map<String, String> result = policy.buildEnvironment(base);
            assertTrue(result.isEmpty());
        }

        @Test
        void shouldExcludeSecretPatterns() {
            var policy = new ShellEnvironmentPolicy(
                    ShellEnvironmentPolicy.InheritMode.ALL, false,  // enable default excludes
                    List.of(), Map.of(), List.of()
            );
            Map<String, String> base = new LinkedHashMap<>();
            base.put("PATH", "/usr/bin");
            base.put("API_KEY", "secret123");
            base.put("DB_SECRET", "password");
            base.put("AUTH_TOKEN", "token");
            base.put("HOME", "/home/user");

            Map<String, String> result = policy.buildEnvironment(base);
            assertTrue(result.containsKey("PATH"));
            assertTrue(result.containsKey("HOME"));
            assertFalse(result.containsKey("API_KEY"));
            assertFalse(result.containsKey("DB_SECRET"));
            assertFalse(result.containsKey("AUTH_TOKEN"));
        }

        @Test
        void shouldApplyCustomExcludes() {
            var policy = new ShellEnvironmentPolicy(
                    ShellEnvironmentPolicy.InheritMode.ALL, true,
                    List.of("CUSTOM_*"), Map.of(), List.of()
            );
            Map<String, String> base = new LinkedHashMap<>();
            base.put("PATH", "/usr/bin");
            base.put("CUSTOM_VAR1", "a");
            base.put("CUSTOM_VAR2", "b");
            base.put("OTHER", "c");

            Map<String, String> result = policy.buildEnvironment(base);
            assertTrue(result.containsKey("PATH"));
            assertTrue(result.containsKey("OTHER"));
            assertFalse(result.containsKey("CUSTOM_VAR1"));
            assertFalse(result.containsKey("CUSTOM_VAR2"));
        }

        @Test
        void shouldSetExplicitValues() {
            var policy = new ShellEnvironmentPolicy(
                    ShellEnvironmentPolicy.InheritMode.NONE, true,
                    List.of(), Map.of("MY_VAR", "explicit"), List.of()
            );
            Map<String, String> result = policy.buildEnvironment(Map.of());
            assertEquals("explicit", result.get("MY_VAR"));
        }

        @Test
        void shouldApplyIncludeOnly() {
            var policy = new ShellEnvironmentPolicy(
                    ShellEnvironmentPolicy.InheritMode.ALL, true,
                    List.of(), Map.of(), List.of("PATH", "HOME")
            );
            Map<String, String> base = Map.of("PATH", "/usr/bin", "HOME", "/home", "OTHER", "x");
            Map<String, String> result = policy.buildEnvironment(base);
            assertEquals(2, result.size());
            assertTrue(result.containsKey("PATH"));
            assertTrue(result.containsKey("HOME"));
        }

        @Test
        void resultShouldBeUnmodifiable() {
            var policy = ShellEnvironmentPolicy.defaults();
            Map<String, String> result = policy.buildEnvironment(Map.of("A", "B"));
            assertThrows(UnsupportedOperationException.class, () -> result.put("X", "Y"));
        }
    }

    // ─── TruncationConfig ──────────────────────────────────────────

    @Nested
    class TruncationConfigTest {

        @Test
        void defaultsShouldHaveCorrectValues() {
            var config = TruncationConfig.defaults();
            assertEquals(40 * 1024, config.defaultMaxOutputBytes().orElse(0));
            assertEquals(1000, config.resolvedMaxLinesRead());
            assertTrue(config.mcpMaxOutputBytes().isEmpty());
        }

        @Test
        void resolvedMaxLinesReadShouldFallbackToDefault() {
            var config = new TruncationConfig(
                    Optional.empty(), Map.of(), Optional.empty(), Optional.empty()
            );
            assertEquals(1000, config.resolvedMaxLinesRead());
        }

        @Test
        void resolvedMaxLinesReadShouldUseConfiguredValue() {
            var config = new TruncationConfig(
                    Optional.empty(), Map.of(), Optional.of(500), Optional.empty()
            );
            assertEquals(500, config.resolvedMaxLinesRead());
        }

        @Test
        void maxOutputBytesForShouldUsePerToolOverride() {
            var config = new TruncationConfig(
                    Optional.of(1024),
                    Map.of("bash", 2048),
                    Optional.empty(), Optional.empty()
            );
            assertEquals(2048, config.maxOutputBytesFor("bash", 512));
            assertEquals(1024, config.maxOutputBytesFor("file", 512));
        }

        @Test
        void maxOutputBytesForShouldFallbackToDefault() {
            var config = new TruncationConfig(
                    Optional.empty(), Map.of(), Optional.empty(), Optional.empty()
            );
            assertEquals(999, config.maxOutputBytesFor("unknown", 999));
        }

        @Test
        void mcpMaxOutputBytesShouldUseMcpSpecificOverride() {
            var config = new TruncationConfig(
                    Optional.of(1024), Map.of(), Optional.empty(), Optional.of(8192)
            );
            assertEquals(8192, config.mcpMaxOutputBytesFor("any-tool", 512));
        }

        @Test
        void interpolateDescriptionShouldReplacePlaceholders() {
            var config = TruncationConfig.defaults();
            String result = config.interpolateDescription(
                    "Reads up to {max_lines_read} lines, max {max_output_bytes} bytes.",
                    "file", 4096
            );
            assertTrue(result.contains("1000"));
            assertTrue(result.contains("40960") || result.contains(String.valueOf(40 * 1024)));
        }
    }

    // ─── GrokConfig ────────────────────────────────────────────────

    @Nested
    class GrokConfigTest {

        @Test
        void defaultsShouldNotBeNull() {
            var config = GrokConfig.defaults();
            assertNotNull(config.model());
            assertNotNull(config.workingDirectory());
            assertNotNull(config.grokHome());
            assertNotNull(config.shellEnvPolicy());
            assertNotNull(config.truncation());
            assertNotNull(config.workflow());
        }

        @Test
        void defaultModelShouldBeOpenAI() {
            var config = GrokConfig.defaults();
            assertEquals("openai", config.model().provider());
        }

        @Test
        void defaultMaxTurnsShouldBe100() {
            assertEquals(100, GrokConfig.defaults().maxTurns());
        }
    }

    // ─── WorkflowConfig ────────────────────────────────────────────

    @Nested
    class WorkflowConfigTest {

        @Test
        void defaultsShouldBeDisabled() {
            var config = WorkflowConfig.defaults();
            assertFalse(config.enabled());
            assertEquals(10, config.defaultAgentBudget());
            assertTrue(config.journalEnabled());
            assertTrue(config.defaultScript().isEmpty());
        }
    }
}
