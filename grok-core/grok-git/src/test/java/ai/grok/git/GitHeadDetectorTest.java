package ai.grok.git;

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
 * Tests for GitHeadDetector.
 * Mirrors the Rust fs_watch.rs git_head_dedup_key tests (2026-08-03 e5478ef sync).
 */
class GitHeadDetectorTest {

    @Nested
    @DisplayName("gitHeadDedupKey identity")
    class DedupKeyIdentity {

        @Test
        @DisplayName("every dimension is part of the identity")
        void everyDimensionIsPartOfIdentity() {
            String base = GitHeadDetector.gitHeadDedupKey("main", false, "/repo", "abc123");

            // Different branch
            assertNotEquals(base,
                    GitHeadDetector.gitHeadDedupKey("dev", false, "/repo", "abc123"));

            // Different worktree status
            assertNotEquals(base,
                    GitHeadDetector.gitHeadDedupKey("main", true, "/repo", "abc123"));

            // Different main repo
            assertNotEquals(base,
                    GitHeadDetector.gitHeadDedupKey("main", false, "/other", "abc123"));

            // A same-branch commit moves HEAD and must not dedup away — the
            // changes panel relies on this to drop the now-committed files.
            assertNotEquals(base,
                    GitHeadDetector.gitHeadDedupKey("main", false, "/repo", "def456"));
        }

        @Test
        @DisplayName("detached HEAD (None branch) is stable and distinct from a real branch")
        void detachedHeadIsStable() {
            assertEquals(
                    GitHeadDetector.gitHeadDedupKey(null, false, null, null),
                    GitHeadDetector.gitHeadDedupKey(null, false, null, null));

            assertNotEquals(
                    GitHeadDetector.gitHeadDedupKey("main", false, "/repo", "abc123"),
                    GitHeadDetector.gitHeadDedupKey(null, false, "/repo", "abc123"));
        }

        @Test
        @DisplayName("swapping branch and main_repo must not collide (NUL separator)")
        void swappingFieldsMustNotCollide() {
            assertNotEquals(
                    GitHeadDetector.gitHeadDedupKey("a", false, "b", null),
                    GitHeadDetector.gitHeadDedupKey("b", false, "a", null));
        }

        @Test
        @DisplayName("empty values produce consistent keys")
        void emptyValuesProduceConsistentKeys() {
            String key1 = GitHeadDetector.gitHeadDedupKey("", false, "", "");
            String key2 = GitHeadDetector.gitHeadDedupKey("", false, "", "");
            assertEquals(key1, key2);
        }
    }

    @Nested
    @DisplayName("Git operations")
    class GitOperations {

        @Test
        @DisplayName("getBranch returns empty for non-git directory")
        void getBranchReturnsEmptyForNonGitDir(@TempDir Path tempDir) {
            GitHeadDetector detector = new GitHeadDetector();
            Optional<String> branch = detector.getBranch(tempDir);
            assertTrue(branch.isEmpty());
        }

        @Test
        @DisplayName("getWorktreeInfo returns empty for non-git directory")
        void getWorktreeInfoReturnsEmptyForNonGitDir(@TempDir Path tempDir) {
            GitHeadDetector detector = new GitHeadDetector();
            Optional<GitHeadDetector.WorktreeInfo> info = detector.getWorktreeInfo(tempDir);
            assertTrue(info.isEmpty());
        }

        @Test
        @DisplayName("getGitInfo returns empty for non-git directory")
        void getGitInfoReturnsEmptyForNonGitDir(@TempDir Path tempDir) {
            GitHeadDetector detector = new GitHeadDetector();
            Optional<GitHeadDetector.GitInfo> info = detector.getGitInfo(tempDir);
            assertTrue(info.isEmpty());
        }

        @Test
        @DisplayName("checkHeadChanged returns empty for non-git directory")
        void checkHeadChangedReturnsEmptyForNonGitDir(@TempDir Path tempDir) {
            GitHeadDetector detector = new GitHeadDetector();
            Optional<String> changed = detector.checkHeadChanged(tempDir, null);
            assertTrue(changed.isEmpty());
        }

        @Test
        @DisplayName("git operations work in actual git repo")
        void gitOperationsWorkInActualRepo() throws IOException {
            // Create a temporary git repo
            Path tempDir = Files.createTempDirectory("git-test");
            try {
                ProcessBuilder initPb = new ProcessBuilder("git", "init");
                initPb.directory(tempDir.toFile());
                Process initProcess = initPb.start();
                initProcess.waitFor();

                ProcessBuilder configPb = new ProcessBuilder("git", "config", "user.email", "test@test.com");
                configPb.directory(tempDir.toFile());
                configPb.start().waitFor();

                ProcessBuilder configNamePb = new ProcessBuilder("git", "config", "user.name", "Test");
                configNamePb.directory(tempDir.toFile());
                configNamePb.start().waitFor();

                // Create a file and commit
                Files.writeString(tempDir.resolve("test.txt"), "hello");
                ProcessBuilder addPb = new ProcessBuilder("git", "add", ".");
                addPb.directory(tempDir.toFile());
                addPb.start().waitFor();

                ProcessBuilder commitPb = new ProcessBuilder("git", "commit", "-m", "initial");
                commitPb.directory(tempDir.toFile());
                commitPb.start().waitFor();

                GitHeadDetector detector = new GitHeadDetector();

                // Now test
                Optional<String> branch = detector.getBranch(tempDir);
                assertTrue(branch.isPresent());
                assertEquals("master", branch.get());

                Optional<String> commit = detector.getCurrentCommit(tempDir);
                assertTrue(commit.isPresent());

                Optional<GitHeadDetector.WorktreeInfo> worktreeInfo = detector.getWorktreeInfo(tempDir);
                assertTrue(worktreeInfo.isPresent());
                assertFalse(worktreeInfo.get().isWorktree());

                Optional<GitHeadDetector.GitInfo> gitInfo = detector.getGitInfo(tempDir);
                assertTrue(gitInfo.isPresent());

                // Check that HEAD changed detection works
                Optional<String> changed = detector.checkHeadChanged(tempDir, null);
                assertTrue(changed.isPresent());

                // Same key should not report changed
                String key = changed.get();
                Optional<String> changedAgain = detector.checkHeadChanged(tempDir, key);
                assertTrue(changedAgain.isEmpty());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Git command interrupted");
            } finally {
                // Cleanup
                deleteRecursively(tempDir);
            }
        }

        private void deleteRecursively(Path path) {
            try {
                if (Files.isDirectory(path)) {
                    Files.list(path).forEach(this::deleteRecursively);
                }
                Files.deleteIfExists(path);
            } catch (IOException e) {
                // Ignore
            }
        }
    }
}
