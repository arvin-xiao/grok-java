package ai.grok.config;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Controls which environment variables agent subprocesses (bash tool, terminals) inherit.
 * Mirrors the Rust ShellEnvironmentPolicy from xai-grok-tools.
 *
 * <p>Policy: start from {@code inherit}; if {@code ignoreDefaultExcludes} is false,
 * drop secret patterns (*KEY*, *SECRET*, *TOKEN*); drop {@code exclude};
 * insert {@code set}; if {@code includeOnly} is non-empty, keep only those.
 * Patterns are case-insensitive globs.
 */
public record ShellEnvironmentPolicy(
        InheritMode inherit,
        boolean ignoreDefaultExcludes,
        List<String> exclude,
        Map<String, String> set,
        List<String> includeOnly
) {
    /**
     * Default secret patterns that are always excluded unless ignoreDefaultExcludes is true.
     */
    private static final List<String> DEFAULT_SECRET_PATTERNS = List.of(
            "*KEY*", "*SECRET*", "*TOKEN*", "*PASSWORD*", "*CREDENTIAL*"
    );
    /**
     * Core platform variables to inherit in CORE mode.
     */
    private static final Set<String> CORE_VARS = Set.of(
            "PATH", "HOME", "USER", "SHELL", "LANG", "LC_ALL", "TERM",
            "TMPDIR", "TEMP", "TMP", "JAVA_HOME", "MAVEN_HOME"
    );

    public static ShellEnvironmentPolicy defaults() {
        return new ShellEnvironmentPolicy(
                InheritMode.ALL,
                true,   // ignoreDefaultExcludes = true (no secret filtering by default)
                List.of(),
                Map.of(),
                List.of()
        );
    }

    /**
     * Simple case-insensitive glob matching.
     */
    private static boolean matchesGlob(String text, String glob) {
        String regex = glob
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).matches();
    }

    /**
     * Build the final environment map for a subprocess.
     */
    public Map<String, String> buildEnvironment(Map<String, String> base) {
        Map<String, String> result = new LinkedHashMap<>();

        // Step 1: Start with inherited environment
        switch (inherit) {
            case ALL -> result.putAll(base);
            case CORE -> {
                for (var entry : base.entrySet()) {
                    if (CORE_VARS.contains(entry.getKey().toUpperCase())) {
                        result.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            case NONE -> {
            } // Start empty
        }

        // Step 2: Apply default secret excludes
        if (!ignoreDefaultExcludes) {
            for (var pattern : DEFAULT_SECRET_PATTERNS) {
                result.keySet().removeIf(key -> matchesGlob(key, pattern));
            }
        }

        // Step 3: Apply custom excludes
        for (var pattern : exclude) {
            result.keySet().removeIf(key -> matchesGlob(key, pattern));
        }

        // Step 4: Insert explicit values
        result.putAll(set);

        // Step 5: If includeOnly is non-empty, keep only matching
        if (!includeOnly.isEmpty()) {
            result.keySet().removeIf(key ->
                    includeOnly.stream().noneMatch(pattern -> matchesGlob(key, pattern))
            );
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * Check if this policy is a no-op (inherits everything unchanged).
     */
    public boolean isNoop() {
        return inherit == InheritMode.ALL
                && ignoreDefaultExcludes
                && exclude.isEmpty()
                && set.isEmpty()
                && includeOnly.isEmpty();
    }

    public enum InheritMode {
        /**
         * Core platform variables only (PATH, HOME, SHELL, ...)
         */
        CORE,
        /**
         * Inherit all environment variables (default)
         */
        ALL,
        /**
         * Start with empty environment
         */
        NONE
    }
}
