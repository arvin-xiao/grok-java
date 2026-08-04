package ai.grok.agent;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP response header preservation during stream collection.
 * Mirrors the Rust fix from 2026-08-03:
 * "sampler: preserve x-should-retry through stream collection"
 *
 * <p>When collecting streamed HTTP responses, certain headers (like x-should-retry)
 * need to be preserved and made available after the stream completes. This utility
 * provides a mechanism to capture and retrieve such headers.
 */
public class StreamHeaderCollector {

    /**
     * Header name for retry control.
     */
    public static final String X_SHOULD_RETRY = "x-should-retry";

    /**
     * Collected headers from the most recent stream.
     */
    private final Map<String, String> collectedHeaders;

    public StreamHeaderCollector() {
        this.collectedHeaders = new ConcurrentHashMap<>();
    }

    /**
     * Convenience method to extract x-should-retry from a response header map.
     *
     * @param headers the response headers
     * @return true if x-should-retry is present and true
     */
    public static boolean extractShouldRetry(Map<String, String> headers) {
        if (headers == null) return false;
        String value = headers.get(X_SHOULD_RETRY);
        if (value == null) {
            // Try case-insensitive lookup
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (X_SHOULD_RETRY.equalsIgnoreCase(entry.getKey())) {
                    value = entry.getValue();
                    break;
                }
            }
        }
        return "true".equalsIgnoreCase(value);
    }

    /**
     * Capture a header value during stream collection.
     * Typically called when the HTTP response headers are first received.
     *
     * @param name  the header name (case-insensitive)
     * @param value the header value
     */
    public void capture(String name, String value) {
        if (name != null && value != null) {
            collectedHeaders.put(name.toLowerCase(), value);
        }
    }

    /**
     * Get a captured header value.
     *
     * @param name the header name (case-insensitive)
     * @return the header value, or empty if not captured
     */
    public Optional<String> get(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(collectedHeaders.get(name.toLowerCase()));
    }

    /**
     * Check if the x-should-retry header indicates a retry is needed.
     * The header value is typically "true" or "false".
     *
     * @return true if the header is present and indicates retry
     */
    public boolean shouldRetry() {
        return get(X_SHOULD_RETRY)
                .map(v -> "true".equalsIgnoreCase(v))
                .orElse(false);
    }

    /**
     * Get all collected headers.
     */
    public Map<String, String> allHeaders() {
        return Map.copyOf(collectedHeaders);
    }

    /**
     * Clear all collected headers (typically after processing).
     */
    public void clear() {
        collectedHeaders.clear();
    }
}
