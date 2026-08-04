package ai.grok.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Client-configurable truncation settings for tool outputs.
 * Mirrors the Rust TruncationConfig from xai-grok-tools.
 *
 * <p>There is deliberately no per-line cap: clipping long lines silently
 * corrupts single-line files (minified JSON, data dumps) with no way for
 * the model to recover the clipped bytes. Non-skill reads are bounded by
 * the whole-read {@code MAX_NUM_TOKENS} cap instead.
 */
public record TruncationConfig(
        /** Max total output bytes for any tool. Default: 40KB. */
        Optional<Integer> defaultMaxOutputBytes,
        /** Per-tool overrides keyed by canonical tool name. */
        Map<String, Integer> perToolMaxOutputBytes,
        /** Max lines to read (read_file). Default: 1000. */
        Optional<Integer> maxLinesRead,
        /** Inline cap for MCP tool results only (bytes). */
        Optional<Integer> mcpMaxOutputBytes
) {
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 40 * 1024; // 40KB
    public static final int DEFAULT_MAX_LINES_READ = 1000;

    public static TruncationConfig defaults() {
        return new TruncationConfig(
                Optional.of(DEFAULT_MAX_OUTPUT_BYTES),
                new HashMap<>(),
                Optional.of(DEFAULT_MAX_LINES_READ),
                Optional.empty()
        );
    }

    /**
     * Resolved max lines per read_file window.
     */
    public int resolvedMaxLinesRead() {
        return maxLinesRead.orElse(DEFAULT_MAX_LINES_READ);
    }

    /**
     * Resolve the max output bytes for a specific tool.
     * Precedence: per-tool override > default override > built-in fallback.
     */
    public int maxOutputBytesFor(String toolName, int builtinDefault) {
        Integer perTool = perToolMaxOutputBytes.get(toolName);
        if (perTool != null) return perTool;
        return defaultMaxOutputBytes.orElse(builtinDefault);
    }

    /**
     * Resolve the max output bytes for an MCP payload.
     * Precedence: per-tool override > MCP-specific override > default override > built-in fallback.
     */
    public int mcpMaxOutputBytesFor(String toolName, int builtinDefault) {
        Integer perTool = perToolMaxOutputBytes.get(toolName);
        if (perTool != null) return perTool;
        return mcpMaxOutputBytes.or(() -> defaultMaxOutputBytes).orElse(builtinDefault);
    }

    /**
     * Replace template placeholders in a tool description with current config values.
     * Recognized placeholders: {max_lines_read}, {max_output_bytes}
     */
    public String interpolateDescription(String description, String toolName, int builtinDefault) {
        return description
                .replace("{max_lines_read}", String.valueOf(resolvedMaxLinesRead()))
                .replace("{max_output_bytes}", String.valueOf(maxOutputBytesFor(toolName, builtinDefault)));
    }
}
