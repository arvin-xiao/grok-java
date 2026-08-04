package ai.grok.agent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Process-global registry of toolset presets.
 * Mirrors the Rust ToolsetPreset system from xai-grok-agent.
 *
 * <p>Each preset is registered as either PUBLIC (product presets enumerated
 * by {@link #publicNames()}) or INTERNAL (resolved by name at runtime but
 * never enumerated).
 */
public class ToolsetPresetRegistry {
    private static final ToolsetPresetRegistry INSTANCE = new ToolsetPresetRegistry();

    private final Map<String, PresetEntry> presets = new ConcurrentHashMap<>();

    public static ToolsetPresetRegistry global() {
        return INSTANCE;
    }

    private static String normalize(String name) {
        return name.toLowerCase().trim();
    }

    /**
     * Register a public (product) toolset preset.
     */
    public void registerPublic(String name, Supplier<Map<String, Object>> builder) {
        presets.put(normalize(name), new PresetEntry(builder, PresetVisibility.PUBLIC));
    }

    /**
     * Register an internal toolset preset (not enumerated publicly).
     */
    public void registerInternal(String name, Supplier<Map<String, Object>> builder) {
        presets.put(normalize(name), new PresetEntry(builder, PresetVisibility.INTERNAL));
    }

    /**
     * Resolve a preset by name (both public and internal).
     */
    public Optional<Map<String, Object>> resolve(String name) {
        PresetEntry entry = presets.get(normalize(name));
        return entry != null ? Optional.of(entry.builder().get()) : Optional.empty();
    }

    /**
     * Enumerate public preset names.
     */
    public List<String> publicNames() {
        return presets.entrySet().stream()
                .filter(e -> e.getValue().visibility() == PresetVisibility.PUBLIC)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /**
     * Check if a preset exists (public or internal).
     */
    public boolean contains(String name) {
        return presets.containsKey(normalize(name));
    }

    public enum PresetVisibility {PUBLIC, INTERNAL}

    public record PresetEntry(Supplier<Map<String, Object>> builder, PresetVisibility visibility) {
    }
}
