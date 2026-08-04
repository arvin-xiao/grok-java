package ai.grok.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Telemetry event logging and metrics collection.
 * Mirrors the Rust `xai-grok-telemetry` and `xai-grok-shell/src/session/telemetry.rs`
 * (2026-08-03 e5478ef sync).
 *
 * <p>Features:
 * <ul>
 *   <li>Session event logging</li>
 *   <li>Skill dispatch tracking with source labels</li>
 *   <li>Skill file identity checking (canonicalization-aware)</li>
 *   <li>Hook name formatting</li>
 *   <li>Session harness metrics</li>
 * </ul>
 */
public final class TelemetrySession {

    private static final Logger log = LoggerFactory.getLogger(TelemetrySession.class);

    private final String sessionId;
    private final List<TelemetryEvent> events = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> skillReadCounts = new ConcurrentHashMap<>();
    private final List<Consumer<TelemetryEvent>> listeners = new CopyOnWriteArrayList<>();
    private Instant startTime;

    /**
     * Types of telemetry events.
     */
    public enum EventType {
        SESSION_START,
        SESSION_END,
        TOOL_CALL,
        SKILL_DISPATCHED,
        SKILL_MD_READ,
        GIT_HEAD_CHANGED,
        TURN_COMPLETE,
        ERROR
    }

    /**
     * Skill trigger types (mirrors Rust SkillTrigger enum).
     */
    public enum SkillTrigger {
        SKILL_TOOL,
        SKILL_MD_READ,
        SLASH_COMMAND,
        AUTO_DISPATCH
    }

    /**
     * A telemetry event.
     */
    public record TelemetryEvent(
            EventType type,
            Instant timestamp,
            Map<String, String> attributes
    ) {
        public static TelemetryEvent of(EventType type, Map<String, String> attributes) {
            return new TelemetryEvent(type, Instant.now(), attributes);
        }

        public static TelemetryEvent of(EventType type) {
            return new TelemetryEvent(type, Instant.now(), Map.of());
        }
    }

    /**
     * Skill dispatched event data.
     */
    public record SkillDispatchedEvent(
            String skillName,
            String pluginSource,
            SkillTrigger trigger
    ) {}

    /**
     * Session harness metrics.
     */
    public record SessionHarnessMetrics(
            int totalTurns,
            int totalToolCalls,
            int totalSkillsDispatched,
            long totalTokensUsed,
            long sessionDurationMs
    ) {}

    public TelemetrySession(String sessionId) {
        this.sessionId = sessionId;
        this.startTime = Instant.now();
    }

    /**
     * Add an event listener.
     */
    public void addListener(Consumer<TelemetryEvent> listener) {
        listeners.add(listener);
    }

    /**
     * Log a telemetry event.
     */
    public void logEvent(TelemetryEvent event) {
        events.add(event);
        for (Consumer<TelemetryEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.warn("Telemetry listener failed", e);
            }
        }
        log.debug("Telemetry event: {} {}", event.type(), event.attributes());
    }

    /**
     * Log a skill dispatched event.
     */
    public void logSkillDispatched(SkillDispatchedEvent event) {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("skill_name", event.skillName());
        attrs.put("plugin_source", event.pluginSource() != null ? event.pluginSource() : "none");
        attrs.put("trigger", event.trigger().name());
        logEvent(TelemetryEvent.of(EventType.SKILL_DISPATCHED, attrs));
    }

    /**
     * Log a skill MD read event (model-side skill read).
     * This counts model-side skill reads in telemetry.
     */
    public void logSkillMdRead(String skillName, String skillSource) {
        skillReadCounts.merge(skillName, 1, Integer::sum);
        Map<String, String> attrs = new HashMap<>();
        attrs.put("skill_name", skillName);
        attrs.put("skill_source", skillSource);
        attrs.put("invocation_trigger", "skill_md_read");
        logEvent(TelemetryEvent.of(EventType.SKILL_MD_READ, attrs));
    }

    /**
     * Get the count of skill reads by skill name.
     */
    public int getSkillReadCount(String skillName) {
        return skillReadCounts.getOrDefault(skillName, 0);
    }

    /**
     * Get all skill read counts.
     */
    public Map<String, Integer> getAllSkillReadCounts() {
        return Map.copyOf(skillReadCounts);
    }

    /**
     * Determine the source label for a skill based on its path.
     * Mirrors Rust `skill_source_label` function.
     *
     * @param skillPath the path to the skill file
     * @param cwd the current working directory
     * @return the source label ("plugin", "project", "user", or "global")
     */
    public static String skillSourceLabel(String skillPath, String cwd) {
        if (skillPath == null) return "unknown";

        // Plugin skills have a plugin_name in their path
        if (skillPath.contains("/plugins/") || skillPath.contains("\\plugins\\")) {
            return "plugin";
        }

        // Project skills are within the cwd
        if (cwd != null && skillPath.startsWith(cwd)) {
            return "project";
        }

        // User skills are in ~/.grok/skills/
        String home = System.getProperty("user.home");
        if (home != null && skillPath.startsWith(home)) {
            return "user";
        }

        return "global";
    }

    /**
     * Check if two skill paths refer to the same file.
     * Canonicalizes both paths; one that cannot be canonicalized (synthetic paths like
     * `chat-product://`) matches only when identical.
     *
     * <p>Mirrors Rust `is_same_skill_file` function (2026-08-03 e5478ef sync).
     *
     * @param skillPath the first skill path
     * @param readPath the second path (from a read operation)
     * @return true if they refer to the same file
     */
    public static boolean isSameSkillFile(java.nio.file.Path skillPath, java.nio.file.Path readPath) {
        if (skillPath.equals(readPath)) {
            return true;
        }

        // If either path is invalid or cannot be resolved, they don't match
        try {
            // Check if paths exist before trying to canonicalize
            if (!java.nio.file.Files.exists(skillPath) || !java.nio.file.Files.exists(readPath)) {
                return false;
            }
            java.nio.file.Path canonicalSkill = skillPath.toRealPath();
            java.nio.file.Path canonicalRead = readPath.toRealPath();
            return canonicalSkill.equals(canonicalRead);
        } catch (java.io.IOException | IllegalArgumentException e) {
            // One or both paths cannot be canonicalized (synthetic paths like chat-product://)
            return false;
        }
    }

    /**
     * Format a hook name from a spec.
     * Mirrors Rust `format_hook_name` function.
     *
     * @param hookSpec the hook spec (format: "scope:name")
     * @return the formatted hook name
     */
    public static String formatHookName(String hookSpec) {
        if (hookSpec == null || hookSpec.isEmpty()) {
            return "unknown";
        }
        String scope = hookSpec.split(":")[0];
        return scope.isEmpty() ? "unknown" : scope;
    }

    /**
     * Get all recorded events.
     */
    public List<TelemetryEvent> getEvents() {
        return List.copyOf(events);
    }

    /**
     * Get session metrics.
     */
    public SessionHarnessMetrics getMetrics() {
        int turns = (int) events.stream().filter(e -> e.type() == EventType.TURN_COMPLETE).count();
        int toolCalls = (int) events.stream().filter(e -> e.type() == EventType.TOOL_CALL).count();
        int skillsDispatched = (int) events.stream().filter(e -> e.type() == EventType.SKILL_DISPATCHED).count();
        long duration = java.time.Duration.between(startTime, Instant.now()).toMillis();

        return new SessionHarnessMetrics(turns, toolCalls, skillsDispatched, 0, duration);
    }

    /**
     * Get the session ID.
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * Mark session start.
     */
    public void markStart() {
        startTime = Instant.now();
        logEvent(TelemetryEvent.of(EventType.SESSION_START, Map.of("session_id", sessionId)));
    }

    /**
     * Mark session end.
     */
    public void markEnd() {
        logEvent(TelemetryEvent.of(EventType.SESSION_END, Map.of("session_id", sessionId)));
    }
}
