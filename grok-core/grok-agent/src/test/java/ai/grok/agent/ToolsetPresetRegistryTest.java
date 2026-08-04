package ai.grok.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ToolsetPresetRegistry.
 */
class ToolsetPresetRegistryTest {

    private ToolsetPresetRegistry registry;

    @BeforeEach
    void setUp() {
        // Use a fresh instance via reflection or just test the global one
        // Since global() returns a singleton, we test it directly
        registry = ToolsetPresetRegistry.global();
    }

    @Test
    void registerPublicShouldBeListed() {
        registry.registerPublic("test-public-" + System.nanoTime(), () -> Map.of("tools", "all"));
        // Should not throw, and the preset should be resolvable
    }

    @Test
    void registerAndResolveShouldWork() {
        String name = "my-preset-" + System.nanoTime();
        registry.registerPublic(name, () -> Map.of("key", "value"));

        var resolved = registry.resolve(name);
        assertTrue(resolved.isPresent());
        assertEquals("value", resolved.get().get("key"));
    }

    @Test
    void resolveShouldReturnEmptyForUnknown() {
        var resolved = registry.resolve("nonexistent-preset-xyz");
        assertTrue(resolved.isEmpty());
    }

    @Test
    void containsShouldReturnTrueForRegistered() {
        String name = "contains-test-" + System.nanoTime();
        registry.registerInternal(name, () -> Map.of());
        assertTrue(registry.contains(name));
        assertFalse(registry.contains("no-such-preset"));
    }

    @Test
    void publicNamesShouldOnlyListPublic() {
        String pubName = "public-listed-" + System.nanoTime();
        String intName = "internal-hidden-" + System.nanoTime();
        registry.registerPublic(pubName, () -> Map.of());
        registry.registerInternal(intName, () -> Map.of());

        var names = registry.publicNames();
        assertTrue(names.contains(pubName));
        assertFalse(names.contains(intName));
    }

    @Test
    void normalizeShouldBeCaseInsensitive() {
        String name = "CaseTest-" + System.nanoTime();
        registry.registerPublic(name, () -> Map.of("a", "b"));

        assertTrue(registry.resolve(name.toLowerCase()).isPresent());
        assertTrue(registry.resolve(name.toUpperCase()).isPresent());
    }

    @Test
    void globalShouldReturnSingleton() {
        assertSame(ToolsetPresetRegistry.global(), ToolsetPresetRegistry.global());
    }
}
