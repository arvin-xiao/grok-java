package ai.grok.skills;

import ai.grok.telemetry.TelemetrySession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SkillRegistry.
 * Mirrors the Rust skills tests (2026-08-03 e5478ef sync).
 */
class SkillRegistryTest {

    @Nested
    @DisplayName("SKILL.md parsing")
    class SkillParsing {

        @Test
        @DisplayName("parses valid SKILL.md with frontmatter")
        void parsesValidSkillMd(@TempDir Path tempDir) throws IOException {
            Path skillFile = tempDir.resolve("SKILL.md");
            Files.writeString(skillFile, """
                    ---
                    name: my-skill
                    description: A test skill
                    version: 1.0
                    ---
                    
                    # My Skill
                    
                    This is the skill content.
                    """);

            TelemetrySession telemetry = new TelemetrySession("test");
            SkillRegistry registry = new SkillRegistry(telemetry);
            var skill = registry.parseSkillFile(skillFile, null);

            assertNotNull(skill);
            assertEquals("my-skill", skill.name());
            assertEquals("A test skill", skill.description());
            assertFalse(skill.isPlugin());
        }

        @Test
        @DisplayName("uses directory name when no name in frontmatter")
        void usesDirectoryNameWhenNoName(@TempDir Path tempDir) throws IOException {
            Path skillDir = tempDir.resolve("auto-named");
            Files.createDirectory(skillDir);
            Path skillFile = skillDir.resolve("SKILL.md");
            Files.writeString(skillFile, """
                    ---
                    description: No name provided
                    ---
                    Content
                    """);

            TelemetrySession telemetry = new TelemetrySession("test");
            SkillRegistry registry = new SkillRegistry(telemetry);
            var skill = registry.parseSkillFile(skillFile, null);

            assertNotNull(skill);
            assertEquals("auto-named", skill.name());
        }

        @Test
        @DisplayName("returns null for file without frontmatter")
        void returnsNullForNoFrontmatter(@TempDir Path tempDir) throws IOException {
            Path skillFile = tempDir.resolve("SKILL.md");
            Files.writeString(skillFile, "# Just a markdown file\n\nNo frontmatter here.");

            TelemetrySession telemetry = new TelemetrySession("test");
            SkillRegistry registry = new SkillRegistry(telemetry);
            var skill = registry.parseSkillFile(skillFile, null);

            assertNull(skill);
        }

        @Test
        @DisplayName("detects plugin skills")
        void detectsPluginSkills(@TempDir Path tempDir) throws IOException {
            Path skillFile = tempDir.resolve("SKILL.md");
            Files.writeString(skillFile, """
                    ---
                    name: plugin-skill
                    ---
                    Content
                    """);

            TelemetrySession telemetry = new TelemetrySession("test");
            SkillRegistry registry = new SkillRegistry(telemetry);
            var skill = registry.parseSkillFile(skillFile, "my-plugin");

            assertNotNull(skill);
            assertTrue(skill.isPlugin());
            assertEquals("my-plugin", skill.pluginName());
        }
    }

    @Nested
    @DisplayName("Skill discovery")
    class SkillDiscovery {

        @Test
        @DisplayName("discovers skills in search path")
        void discoversSkillsInSearchPath(@TempDir Path tempDir) throws IOException {
            // Create a skill directory structure
            Path skillDir = tempDir.resolve("my-skill");
            Files.createDirectory(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                    ---
                    name: discovered-skill
                    description: Found by discovery
                    ---
                    Content
                    """);

            TelemetrySession telemetry = new TelemetrySession("test");
            SkillRegistry registry = new SkillRegistry(telemetry);
            registry.addSearchPath(tempDir);

            int count = registry.discoverSkills();
            assertEquals(1, count);

            var skill = registry.getSkill("discovered-skill");
            assertTrue(skill.isPresent());
            assertEquals("Found by discovery", skill.get().description());
        }

        @Test
        @DisplayName("discovers multiple skills")
        void discoversMultipleSkills(@TempDir Path tempDir) throws IOException {
            for (int i = 0; i < 3; i++) {
                Path skillDir = tempDir.resolve("skill-" + i);
                Files.createDirectory(skillDir);
                Files.writeString(skillDir.resolve("SKILL.md"), """
                        ---
                        name: skill-%d
                        ---
                        Content
                        """.formatted(i));
            }

            TelemetrySession telemetry = new TelemetrySession("test");
            SkillRegistry registry = new SkillRegistry(telemetry);
            registry.addSearchPath(tempDir);

            int count = registry.discoverSkills();
            assertEquals(3, count);
            assertEquals(3, registry.getAllSkills().size());
        }
    }

    @Nested
    @DisplayName("Skill dispatch")
    class SkillDispatch {

        @Test
        @DisplayName("dispatch logs telemetry event")
        void dispatchLogsTelemetryEvent(@TempDir Path tempDir) throws IOException {
            Path skillFile = tempDir.resolve("SKILL.md");
            Files.writeString(skillFile, """
                    ---
                    name: test-skill
                    ---
                    Skill content here
                    """);

            TelemetrySession telemetry = new TelemetrySession("test");
            SkillRegistry registry = new SkillRegistry(telemetry);
            registry.addSearchPath(tempDir);
            registry.discoverSkills();

            var result = registry.dispatch("test-skill", TelemetrySession.SkillTrigger.SKILL_TOOL);

            assertTrue(result.success());
            assertEquals("test-skill", result.skillName());
            assertNotNull(result.output());

            // Check telemetry was logged
            assertEquals(1, telemetry.getEvents().stream()
                    .filter(e -> e.type() == TelemetrySession.EventType.SKILL_DISPATCHED)
                    .count());
        }

        @Test
        @DisplayName("dispatch fails for unknown skill")
        void dispatchFailsForUnknownSkill() {
            TelemetrySession telemetry = new TelemetrySession("test");
            SkillRegistry registry = new SkillRegistry(telemetry);

            var result = registry.dispatch("nonexistent", TelemetrySession.SkillTrigger.SKILL_TOOL);

            assertFalse(result.success());
            assertNotNull(result.error());
        }
    }

    @Nested
    @DisplayName("Skill resolution from read path")
    class SkillResolution {

        @Test
        @DisplayName("resolves skill from SKILL.md read path")
        void resolvesSkillFromReadPath(@TempDir Path tempDir) throws IOException {
            Path skillFile = tempDir.resolve("SKILL.md");
            Files.writeString(skillFile, """
                    ---
                    name: readable-skill
                    ---
                    Content
                    """);

            TelemetrySession telemetry = new TelemetrySession("test");
            SkillRegistry registry = new SkillRegistry(telemetry);
            registry.addSearchPath(tempDir);
            registry.discoverSkills();

            var skill = registry.resolveSkillFromReadPath(skillFile, tempDir);

            assertTrue(skill.isPresent());
            assertEquals("readable-skill", skill.get().name());

            // Check telemetry was logged
            assertEquals(1, telemetry.getSkillReadCount("readable-skill"));
        }

        @Test
        @DisplayName("returns empty for non-SKILL.md file")
        void returnsEmptyForNonSkillFile(@TempDir Path tempDir) throws IOException {
            Path otherFile = tempDir.resolve("README.md");
            Files.writeString(otherFile, "# Readme");

            TelemetrySession telemetry = new TelemetrySession("test");
            SkillRegistry registry = new SkillRegistry(telemetry);

            var skill = registry.resolveSkillFromReadPath(otherFile, tempDir);
            assertTrue(skill.isEmpty());
        }
    }

    @Nested
    @DisplayName("SkillInfo")
    class SkillInfoTests {

        @Test
        @DisplayName("isPlugin returns true for plugin skills")
        void isPluginReturnsTrueForPluginSkills() {
            var skill = new SkillRegistry.SkillInfo("name", "desc", "/path", "plugin", null);
            assertTrue(skill.isPlugin());
        }

        @Test
        @DisplayName("isPlugin returns false for non-plugin skills")
        void isPluginReturnsFalseForNonPluginSkills() {
            var skill = new SkillRegistry.SkillInfo("name", "desc", "/path", null, null);
            assertFalse(skill.isPlugin());
        }

        @Test
        @DisplayName("isPlugin returns false for empty plugin name")
        void isPluginReturnsFalseForEmptyPluginName() {
            var skill = new SkillRegistry.SkillInfo("name", "desc", "/path", "", null);
            assertFalse(skill.isPlugin());
        }
    }
}
