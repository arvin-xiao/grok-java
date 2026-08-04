package ai.grok.tools.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TaskLogTruncator.
 */
class TaskLogTruncatorTest {

    @Nested
    @DisplayName("Basic truncation")
    class BasicTruncation {

        @Test
        @DisplayName("null input returns null")
        void nullInputReturnsNull() {
            var truncator = new TaskLogTruncator();
            assertNull(truncator.truncate(null));
        }

        @Test
        @DisplayName("short input returns unchanged")
        void shortInputReturnsUnchanged() {
            var truncator = new TaskLogTruncator();
            String input = "Hello, world!";
            assertEquals(input, truncator.truncate(input));
        }

        @Test
        @DisplayName("input at max length returns unchanged")
        void inputAtMaxLengthReturnsUnchanged() {
            var truncator = new TaskLogTruncator(100, 50, 40);
            String input = "a".repeat(100);
            assertEquals(input, truncator.truncate(input));
        }

        @Test
        @DisplayName("input exceeding max length is truncated")
        void inputExceedingMaxLengthIsTruncated() {
            var truncator = new TaskLogTruncator(100, 50, 40);
            String input = "a".repeat(200);
            String result = truncator.truncate(input);

            assertTrue(result.length() < input.length());
            // omitted = 200 - 50 - 40 = 110
            assertTrue(result.contains("[110 characters truncated]"));
        }
    }

    @Nested
    @DisplayName("Head and tail preservation")
    class HeadTailPreservation {

        @Test
        @DisplayName("preserves head and tail of long input")
        void preservesHeadAndTail() {
            var truncator = new TaskLogTruncator(20, 8, 8);
            // Must exceed maxLength (20) to trigger truncation
            String input = "HEAD_MIDxxxxxxxxxxxxTAIL_END";
            String result = truncator.truncate(input);

            assertTrue(result.startsWith("HEAD_MID"));
            assertTrue(result.endsWith("TAIL_END"));
            assertTrue(result.contains("characters truncated"));
        }

        @Test
        @DisplayName("default truncator uses 10000 char limit")
        void defaultTruncatorUses10000CharLimit() {
            var truncator = new TaskLogTruncator();
            assertEquals(10_000, truncator.maxLength());
        }
    }

    @Nested
    @DisplayName("wouldTruncate check")
    class WouldTruncateCheck {

        @Test
        @DisplayName("returns false for null")
        void returnsFalseForNull() {
            var truncator = new TaskLogTruncator();
            assertFalse(truncator.wouldTruncate(null));
        }

        @Test
        @DisplayName("returns false for short input")
        void returnsFalseForShortInput() {
            var truncator = new TaskLogTruncator();
            assertFalse(truncator.wouldTruncate("short"));
        }

        @Test
        @DisplayName("returns true for long input")
        void returnsTrueForLongInput() {
            var truncator = new TaskLogTruncator(10, 5, 4);
            assertTrue(truncator.wouldTruncate("a".repeat(20)));
        }

        @Test
        @DisplayName("returns false for input at exact max length")
        void returnsFalseForExactMaxLength() {
            var truncator = new TaskLogTruncator(10, 5, 4);
            assertFalse(truncator.wouldTruncate("a".repeat(10)));
        }
    }

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("rejects negative maxLength")
        void rejectsNegativeMaxLength() {
            assertThrows(IllegalArgumentException.class,
                () -> new TaskLogTruncator(-1, 5, 4));
        }

        @Test
        @DisplayName("rejects negative headSize")
        void rejectsNegativeHeadSize() {
            assertThrows(IllegalArgumentException.class,
                () -> new TaskLogTruncator(100, -1, 4));
        }

        @Test
        @DisplayName("rejects negative tailSize")
        void rejectsNegativeTailSize() {
            assertThrows(IllegalArgumentException.class,
                () -> new TaskLogTruncator(100, 5, -1));
        }

        @Test
        @DisplayName("rejects head+tail exceeding maxLength")
        void rejectsHeadTailExceedingMaxLength() {
            assertThrows(IllegalArgumentException.class,
                () -> new TaskLogTruncator(10, 6, 6));
        }
    }

    @Nested
    @DisplayName("Static convenience method")
    class StaticConvenienceMethod {

        @Test
        @DisplayName("truncateDefault works with short input")
        void truncateDefaultWorksWithShortInput() {
            assertEquals("hello", TaskLogTruncator.truncateDefault("hello"));
        }

        @Test
        @DisplayName("truncateDefault works with null")
        void truncateDefaultWorksWithNull() {
            assertNull(TaskLogTruncator.truncateDefault(null));
        }

        @Test
        @DisplayName("truncateDefault truncates long input")
        void truncateDefaultTruncatesLongInput() {
            String longInput = "x".repeat(20_000);
            String result = TaskLogTruncator.truncateDefault(longInput);
            assertTrue(result.length() < longInput.length());
            assertTrue(result.contains("characters truncated"));
        }
    }
}
