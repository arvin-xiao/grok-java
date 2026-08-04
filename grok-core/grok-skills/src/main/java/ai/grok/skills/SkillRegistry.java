package ai.grok.skills;

import ai.grok.telemetry.TelemetrySession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Skills system for SKILL.md parsing and dispatch.
 * Mirrors the Rust `xai-grok-tools/src/implementations/skills/` module
 * (2026-08-03 e5478ef sync).
 *
 * <p>Features:
 * <ul>
 *   <li>SKILL.md frontmatter parsing</li>
 *   <li>Skill discovery from multiple sources (project, user, plugins)</li>
 *   <li>Skill dispatch with telemetry tracking</li>
 *   <li>Skill file identity checking</li>
 * </ul>
 */
public final class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);
    private static final String SKILL_FILENAME = "SKILL.md";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, SkillInfo> skills = new ConcurrentHashMap<>();
    private final TelemetrySession telemetry;
    private final List<Path> searchPaths = new ArrayList<>();

    /**
     * Parsed skill information from SKILL.md frontmatter.
     */
    public record SkillInfo(
            String name,
            String description,
            String path,
            String pluginName,
            Map<String, Object> metadata
    ) {
        public boolean isPlugin() {
            return pluginName != null && !pluginName.isEmpty();
        }
    }

    /**
     * Skill dispatch result.
     */
    public record DispatchResult(
            boolean success,
            String skillName,
            String output,
            String error
    ) {
        public static DispatchResult success(String skillName, String output) {
            return new DispatchResult(true, skillName, output, null);
        }

        public static DispatchResult failure(String skillName, String error) {
            return new DispatchResult(false, skillName, null, error);
        }
    }

    public SkillRegistry(TelemetrySession telemetry) {
        this.telemetry = telemetry;
    }

    /**
     * Add a search path for discovering skills.
     */
    public void addSearchPath(Path path) {
        searchPaths.add(path);
        log.debug("Added skill search path: {}", path);
    }

    /**
     * Discover and load all skills from search paths.
     */
    public int discoverSkills() {
        int count = 0;
        for (Path searchPath : searchPaths) {
            try {
                count += discoverSkillsInPath(searchPath, null);
            } catch (IOException e) {
                log.warn("Failed to discover skills in: {}", searchPath, e);
            }
        }
        log.info("Discovered {} skills", count);
        return count;
    }

    /**
     * Get a skill by name.
     */
    public Optional<SkillInfo> getSkill(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    /**
     * Get all registered skills.
     */
    public Collection<SkillInfo> getAllSkills() {
        return List.copyOf(skills.values());
    }

    /**
     * Register a skill manually.
     */
    public void registerSkill(SkillInfo skill) {
        skills.put(skill.name(), skill);
        log.debug("Registered skill: {} at {}", skill.name(), skill.path());
    }

    /**
     * Dispatch a skill by name.
     *
     * <p>Logs a SkillDispatched telemetry event with the appropriate trigger.
     *
     * @param skillName the skill to dispatch
     * @param trigger the trigger type
     * @return dispatch result
     */
    public DispatchResult dispatch(String skillName, TelemetrySession.SkillTrigger trigger) {
        SkillInfo skill = skills.get(skillName);
        if (skill == null) {
            return DispatchResult.failure(skillName, "Skill not found: " + skillName);
        }

        // Log telemetry
        if (telemetry != null) {
            telemetry.logSkillDispatched(new TelemetrySession.SkillDispatchedEvent(
                    skill.name(),
                    skill.pluginName(),
                    trigger
            ));
        }

        // In a real implementation, this would execute the skill
        // For now, just return success with the skill content
        try {
            String content = Files.readString(Path.of(skill.path()));
            return DispatchResult.success(skillName, content);
        } catch (IOException e) {
            return DispatchResult.failure(skillName, "Failed to read skill: " + e.getMessage());
        }
    }

    /**
     * Resolve a skill from a read path (for SKILL.md reads).
     *
     * <p>This is used when the model reads a SKILL.md file directly,
     * to track which skill was activated.
     *
     * @param readPath the path that was read
     * @param cwd the current working directory
     * @return the matching skill, or empty if not a skill file
     */
    public Optional<SkillInfo> resolveSkillFromReadPath(Path readPath, Path cwd) {
        // Only match SKILL.md files
        if (!readPath.getFileName().toString().equals(SKILL_FILENAME)) {
            return Optional.empty();
        }

        // Try to canonicalize the path
        Path canonicalReadPath;
        try {
            canonicalReadPath = readPath.toRealPath();
        } catch (IOException e) {
            canonicalReadPath = readPath.toAbsolutePath().normalize();
        }

        // Find matching skill
        for (SkillInfo skill : skills.values()) {
            Path skillPath = Path.of(skill.path());
            if (TelemetrySession.isSameSkillFile(skillPath, canonicalReadPath)) {
                // Log skill MD read
                if (telemetry != null) {
                    String source = skill.isPlugin() ? "plugin"
                            : TelemetrySession.skillSourceLabel(skill.path(), cwd.toString());
                    telemetry.logSkillMdRead(skill.name(), source);
                }
                return Optional.of(skill);
            }
        }

        return Optional.empty();
    }

    private int discoverSkillsInPath(Path basePath, String pluginName) throws IOException {
        if (!Files.isDirectory(basePath)) {
            return 0;
        }

        int count = 0;
        try (Stream<Path> paths = Files.walk(basePath, 5)) {
            List<Path> skillFiles = paths
                    .filter(p -> p.getFileName().toString().equals(SKILL_FILENAME))
                    .toList();

            for (Path skillFile : skillFiles) {
                try {
                    SkillInfo skill = parseSkillFile(skillFile, pluginName);
                    if (skill != null) {
                        skills.put(skill.name(), skill);
                        count++;
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse skill file: {}", skillFile, e);
                }
            }
        }

        return count;
    }

    /**
     * Parse a SKILL.md file and extract frontmatter.
     */
    SkillInfo parseSkillFile(Path skillFile, String pluginName) throws IOException {
        String content = Files.readString(skillFile);

        // Look for YAML frontmatter between --- markers
        if (!content.startsWith("---")) {
            log.debug("No frontmatter in: {}", skillFile);
            return null;
        }

        int endIndex = content.indexOf("---", 3);
        if (endIndex < 0) {
            log.debug("Unclosed frontmatter in: {}", skillFile);
            return null;
        }

        String frontmatter = content.substring(3, endIndex).trim();

        // Simple YAML parsing for name and description
        String name = null;
        String description = null;
        Map<String, Object> metadata = new HashMap<>();

        for (String line : frontmatter.split("\n")) {
            line = line.trim();
            if (line.startsWith("name:")) {
                name = line.substring(5).trim().replace("\"", "").replace("'", "");
            } else if (line.startsWith("description:")) {
                description = line.substring(12).trim().replace("\"", "").replace("'", "");
            } else if (line.contains(":")) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    metadata.put(parts[0].trim(), parts[1].trim());
                }
            }
        }

        if (name == null || name.isEmpty()) {
            name = skillFile.getParent().getFileName().toString();
        }

        return new SkillInfo(name, description, skillFile.toString(), pluginName, metadata);
    }
}
