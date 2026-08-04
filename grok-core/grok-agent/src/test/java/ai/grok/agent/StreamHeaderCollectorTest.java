package ai.grok.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StreamHeaderCollector.
 */
class StreamHeaderCollectorTest {

    @Nested
    @DisplayName("Header capture and retrieval")
    class HeaderCaptureAndRetrieval {

        private StreamHeaderCollector collector;

        @BeforeEach
        void setUp() {
            collector = new StreamHeaderCollector();
        }

        @Test
        @DisplayName("capture and retrieve header")
        void captureAndRetrieveHeader() {
            collector.capture("Content-Type", "application/json");
            var result = collector.get("content-type");
            assertTrue(result.isPresent());
            assertEquals("application/json", result.get());
        }

        @Test
        @DisplayName("headers are case-insensitive")
        void headersAreCaseInsensitive() {
            collector.capture("X-Should-Retry", "true");
            assertTrue(collector.get("x-should-retry").isPresent());
            assertTrue(collector.get("X-SHOULD-RETRY").isPresent());
            assertTrue(collector.get("X-Should-Retry").isPresent());
        }

        @Test
        @DisplayName("null name is ignored")
        void nullNameIsIgnored() {
            collector.capture(null, "value");
            assertTrue(collector.allHeaders().isEmpty());
        }

        @Test
        @DisplayName("null value is ignored")
        void nullValueIsIgnored() {
            collector.capture("name", null);
            assertTrue(collector.allHeaders().isEmpty());
        }

        @Test
        @DisplayName("get returns empty for missing header")
        void getReturnsEmptyForMissingHeader() {
            assertTrue(collector.get("nonexistent").isEmpty());
        }

        @Test
        @DisplayName("get returns empty for null name")
        void getReturnsEmptyForNullName() {
            assertTrue(collector.get(null).isEmpty());
        }
    }

    @Nested
    @DisplayName("x-should-retry handling")
    class XShouldRetryHandling {

        private StreamHeaderCollector collector;

        @BeforeEach
        void setUp() {
            collector = new StreamHeaderCollector();
        }

        @Test
        @DisplayName("shouldRetry returns false when header not present")
        void shouldRetryReturnsFalseWhenNotPresent() {
            assertFalse(collector.shouldRetry());
        }

        @Test
        @DisplayName("shouldRetry returns true when header is 'true'")
        void shouldRetryReturnsTrueWhenTrue() {
            collector.capture("x-should-retry", "true");
            assertTrue(collector.shouldRetry());
        }

        @Test
        @DisplayName("shouldRetry returns true for case-insensitive 'TRUE'")
        void shouldRetryReturnsTrueForUpperCase() {
            collector.capture("x-should-retry", "TRUE");
            assertTrue(collector.shouldRetry());
        }

        @Test
        @DisplayName("shouldRetry returns false when header is 'false'")
        void shouldRetryReturnsFalseWhenFalse() {
            collector.capture("x-should-retry", "false");
            assertFalse(collector.shouldRetry());
        }

        @Test
        @DisplayName("shouldRetry returns false for invalid value")
        void shouldRetryReturnsFalseForInvalidValue() {
            collector.capture("x-should-retry", "yes");
            assertFalse(collector.shouldRetry());
        }
    }

    @Nested
    @DisplayName("allHeaders and clear")
    class AllHeadersAndClear {

        @Test
        @DisplayName("allHeaders returns copy of captured headers")
        void allHeadersReturnsCopy() {
            var collector = new StreamHeaderCollector();
            collector.capture("h1", "v1");
            collector.capture("h2", "v2");

            Map<String, String> headers = collector.allHeaders();
            assertEquals(2, headers.size());
            assertEquals("v1", headers.get("h1"));
            assertEquals("v2", headers.get("h2"));
        }

        @Test
        @DisplayName("allHeaders returns unmodifiable map")
        void allHeadersReturnsUnmodifiableMap() {
            var collector = new StreamHeaderCollector();
            collector.capture("h1", "v1");

            Map<String, String> headers = collector.allHeaders();
            assertThrows(UnsupportedOperationException.class,
                () -> headers.put("h2", "v2"));
        }

        @Test
        @DisplayName("clear removes all headers")
        void clearRemovesAllHeaders() {
            var collector = new StreamHeaderCollector();
            collector.capture("h1", "v1");
            collector.capture("x-should-retry", "true");

            collector.clear();

            assertTrue(collector.allHeaders().isEmpty());
            assertFalse(collector.shouldRetry());
        }
    }

    @Nested
    @DisplayName("Static extractShouldRetry")
    class StaticExtractShouldRetry {

        @Test
        @DisplayName("returns false for null map")
        void returnsFalseForNullMap() {
            assertFalse(StreamHeaderCollector.extractShouldRetry(null));
        }

        @Test
        @DisplayName("returns false for empty map")
        void returnsFalseForEmptyMap() {
            assertFalse(StreamHeaderCollector.extractShouldRetry(Map.of()));
        }

        @Test
        @DisplayName("returns true when x-should-retry is 'true'")
        void returnsTrueWhenHeaderIsTrue() {
            Map<String, String> headers = Map.of("x-should-retry", "true");
            assertTrue(StreamHeaderCollector.extractShouldRetry(headers));
        }

        @Test
        @DisplayName("returns false when x-should-retry is 'false'")
        void returnsFalseWhenHeaderIsFalse() {
            Map<String, String> headers = Map.of("x-should-retry", "false");
            assertFalse(StreamHeaderCollector.extractShouldRetry(headers));
        }

        @Test
        @DisplayName("handles case-insensitive header name")
        void handlesCaseInsensitiveHeaderName() {
            Map<String, String> headers = new HashMap<>();
            headers.put("X-Should-Retry", "true");
            assertTrue(StreamHeaderCollector.extractShouldRetry(headers));
        }

        @Test
        @DisplayName("returns false when header is absent")
        void returnsFalseWhenHeaderAbsent() {
            Map<String, String> headers = Map.of("other-header", "value");
            assertFalse(StreamHeaderCollector.extractShouldRetry(headers));
        }
    }
}
