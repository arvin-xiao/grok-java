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
}
