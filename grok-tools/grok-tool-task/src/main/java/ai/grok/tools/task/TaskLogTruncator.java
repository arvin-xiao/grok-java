package ai.grok.tools.task;

/**
 * Utility for truncating task log output.
 * Mirrors the Rust `util/truncate.rs` from xai-grok-tools (2026-08-03 sync).
 *
 * <p>Keeps a large task log from making the completion message too long.
 * Uses a configurable maximum length with head+tail truncation strategy.
 */
public final class TaskLogTruncator {

    /**
     * Default maximum length for task log output (in characters).
     */
    public static final int DEFAULT_MAX_LENGTH = 10_000;

    /**
     * Default head size when truncating (characters from the start).
     */
    public static final int DEFAULT_HEAD_SIZE = 5_000;

    /**
     * Default tail size when truncating (characters from the end).
     */
    public static final int DEFAULT_TAIL_SIZE = 4_500;

    private final int maxLength;
    private final int headSize;
    private final int tailSize;

    public TaskLogTruncator() {
        this(DEFAULT_MAX_LENGTH, DEFAULT_HEAD_SIZE, DEFAULT_TAIL_SIZE);
    }

    public TaskLogTruncator(int maxLength, int headSize, int tailSize) {
        if (maxLength < 0 || headSize < 0 || tailSize < 0) {
            throw new IllegalArgumentException("Sizes must be non-negative");
        }
        if (headSize + tailSize > maxLength) {
            throw new IllegalArgumentException("headSize + tailSize must not exceed maxLength");
        }
        this.maxLength = maxLength;
        this.headSize = headSize;
        this.tailSize = tailSize;
    }

    /**
     * Convenience static method using default settings.
     */
    public static String truncateDefault(String input) {
        return new TaskLogTruncator().truncate(input);
    }

    /**
     * Truncate the input if it exceeds the maximum length.
     * Uses head+tail strategy: keeps the first `headSize` and last `tailSize` characters,
     * with a truncation marker in between.
     *
     * @param input the text to potentially truncate
     * @return the truncated text, or the original if within limits
     */
    public String truncate(String input) {
        if (input == null || input.length() <= maxLength) {
            return input;
        }

        String head = input.substring(0, headSize);
        String tail = input.substring(input.length() - tailSize);
        int omitted = input.length() - headSize - tailSize;

        return head + "\n\n... [" + omitted + " characters truncated] ...\n\n" + tail;
    }

    /**
     * Check if the input would be truncated.
     */
    public boolean wouldTruncate(String input) {
        return input != null && input.length() > maxLength;
    }

    /**
     * Get the configured maximum length.
     */
    public int maxLength() {
        return maxLength;
    }

    // ─── Partial Output Support (2026-08-03 sync) ───────────────

    /**
     * Represents a partial output with known total size.
     * Used when we only have part of a larger output stream.
     */
    public record PartialOutput(String text, long totalBytes) {
        /**
         * Create a partial output that represents only part of a larger output.
         */
        public static PartialOutput partOf(String text, long totalBytes) {
            return new PartialOutput(text, totalBytes);
        }

        /**
         * Create a complete output (text is the entire content).
         */
        public static PartialOutput complete(String text) {
            return new PartialOutput(text, text.length());
        }

        /**
         * Check if this partial output contains the complete content.
         */
        public boolean isComplete() {
            return totalBytes <= text.length();
        }
    }

    /**
     * Result of truncation with preview.
     */
    public record TruncationResult(String text, boolean wasTruncated) {}

    /**
     * Truncate with preview support for partial outputs.
     * 
     * <p>When the text is part of a larger output (even if it fits within maxLength),
     * the result includes a footer stating the total size. This mirrors the Rust
     * truncate_with_preview behavior from 2026-08-03.
     *
     * @param output the partial output to truncate
     * @param previewBytes maximum bytes for the preview portion
     * @param footerHint optional hint to append to the footer
     * @return truncation result with text and whether it was truncated
     */
    public TruncationResult truncateWithPreview(PartialOutput output, int previewBytes, String footerHint) {
        if (output == null || output.text() == null) {
            return new TruncationResult("", false);
        }

        String text = output.text();
        long totalBytes = output.totalBytes();
        boolean isWhole = output.isComplete();

        // If the output is complete and fits within limit, no truncation needed
        if (isWhole && text.length() <= maxLength) {
            return new TruncationResult(text, false);
        }

        // Build footer with total size
        String footer;
        if (footerHint != null && !footerHint.isEmpty()) {
            footer = String.format("[Output truncated - %d bytes total. %s]", totalBytes, footerHint);
        } else {
            footer = String.format("[Output truncated - %d bytes total]", totalBytes);
        }

        // Text that fits the limit can still be part of a larger output;
        // the reader still needs the total size and where to find the rest.
        if (text.length() <= maxLength) {
            return new TruncationResult(text + "\n\n" + footer, true);
        }

        // Text exceeds limit: truncate with head+tail strategy
        int actualPreview = Math.min(previewBytes, text.length());
        String preview;
        if (actualPreview >= text.length()) {
            preview = text;
        } else {
            int headLen = actualPreview * 2 / 3;  // 2/3 from head
            int tailLen = actualPreview / 3;       // 1/3 from tail
            String head = text.substring(0, headLen);
            String tail = text.substring(text.length() - tailLen);
            preview = head + "\n... [truncated] ...\n" + tail;
        }

        return new TruncationResult(preview + "\n\n" + footer, true);
    }

    /**
     * Convenience method for truncateWithPreview with default preview size.
     */
    public TruncationResult truncateWithPreview(PartialOutput output) {
        return truncateWithPreview(output, maxLength / 2, null);
    }
}
