package ai.grok.tool.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;

/**
 * Typed tool output values. Mirrors the Rust ToolOutput enum from xai-grok-tools.
 *
 * <p>Tools return structured output so downstream consumers (prompts, telemetry,
 * MCP framing) can handle each variant without string sniffing.
 */
public sealed interface ToolOutput {

    /**
     * Convert this output to a plain text representation for LLM consumption.
     */
    default String toText() {
        return switch (this) {
            case Text t -> t.text();
            case Dynamic d -> d.value().toString();
            case MediaGen m -> "Saved to: " + m.path() + " (filename: " + m.filename() + ")";
        };
    }

    /**
     * Plain text output (the common case).
     */
    record Text(String text) implements ToolOutput {
        public Text {
            if (text == null) text = "";
        }

        public static Text of(String text) {
            return new Text(text);
        }
    }

    /**
     * Structured JSON output for programmatic consumers.
     */
    record Dynamic(JsonNode value) implements ToolOutput {
        public Dynamic {
            if (value == null) throw new IllegalArgumentException("value must not be null");
        }

        public static Dynamic of(JsonNode value) {
            return new Dynamic(value);
        }
    }

    /**
     * Output from media generation tools (image_gen, video_gen, image_edit).
     */
    record MediaGen(Path path, String filename, String sessionFolder) implements ToolOutput {
        public MediaGen {
            if (path == null) throw new IllegalArgumentException("path must not be null");
            if (filename == null) filename = path.getFileName().toString();
            if (sessionFolder == null) sessionFolder = "";
        }

        public static MediaGen of(Path path) {
            return new MediaGen(path, path.getFileName().toString(), "");
        }
    }
}
