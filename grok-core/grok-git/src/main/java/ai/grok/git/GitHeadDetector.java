package ai.grok.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Git operations for HEAD change detection.
 * Mirrors the Rust `xai-grok-shell/src/session/fs_watch.rs` git-related functions
 * and `xai-grok-workspace/src/session/git.rs` (2026-08-03 e5478ef sync).
 *
 * <p>Provides:
 * <ul>
 *   <li>Branch detection</li>
 *   <li>Worktree info (is worktree? main repo path)</li>
 *   <li>Current commit SHA</li>
 *   <li>HEAD dedup key computation (branch + worktree + main_repo + commit)</li>
 * </ul>
 */
public final class GitHeadDetector {

    private static final Logger log = LoggerFactory.getLogger(GitHeadDetector.class);
    private static final long GIT_TIMEOUT_SECONDS = 5;

    /**
     * Information about a git worktree.
     */
    public record WorktreeInfo(boolean isWorktree, String mainRepo) {}

    /**
     * Result of getting git info for a directory.
     */
    public record GitInfo(String branch, WorktreeInfo worktreeInfo, String commit) {}

    /**
     * Get the current branch name.
     *
     * @param cwd the working directory
     * @return the branch name, or empty if not in a git repo
     */
    public Optional<String> getBranch(Path cwd) {
        return runGitCommand(cwd, "rev-parse", "--abbrev-ref", "HEAD");
    }

    /**
     * Get the current commit SHA (short).
     *
     * @param cwd the working directory
     * @return the commit SHA, or empty if not in a git repo
     */
    public Optional<String> getCurrentCommit(Path cwd) {
        return runGitCommand(cwd, "rev-parse", "--short", "HEAD");
    }

    /**
     * Get worktree information.
     *
     * @param cwd the working directory
     * @return worktree info, or empty if not in a git repo
     */
    public Optional<WorktreeInfo> getWorktreeInfo(Path cwd) {
        // Check if we're in a git repo at all
        Optional<String> gitDir = runGitCommand(cwd, "rev-parse", "--git-dir");
        if (gitDir.isEmpty()) {
            return Optional.empty();
        }

        String gitDirPath = gitDir.get();
        boolean isWorktree = !gitDirPath.equals(".git");

        // Get the main repo path
        Optional<String> mainRepo = runGitCommand(cwd, "rev-parse", "--show-toplevel");

        return Optional.of(new WorktreeInfo(isWorktree, mainRepo.orElse(null)));
    }

    /**
     * Get all git info in one call.
     *
     * @param cwd the working directory
     * @return git info, or empty if not in a git repo
     */
    public Optional<GitInfo> getGitInfo(Path cwd) {
        Optional<String> branch = getBranch(cwd);
        Optional<WorktreeInfo> worktreeInfo = getWorktreeInfo(cwd);
        Optional<String> commit = getCurrentCommit(cwd);

        if (branch.isEmpty() || worktreeInfo.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new GitInfo(branch.get(), worktreeInfo.get(), commit.orElse(null)));
    }

    /**
     * Compute a dedup key for git HEAD changes.
     *
     * <p>The key includes branch, worktree status, main repo path, and commit SHA.
     * This ensures that same-branch commits (agent runs `git commit`) still trigger
     * notifications — the changes panel must drop the now-committed files.
     *
     * <p>Uses NUL separator which is illegal in git refs and paths, so fields can't collide.
     *
     * @param branch the branch name (may be null for detached HEAD)
     * @param isWorktree whether this is a worktree
     * @param mainRepo the main repository path (may be null)
     * @param commit the commit SHA (may be null)
     * @return the dedup key
     */
    public static String gitHeadDedupKey(String branch, boolean isWorktree, String mainRepo, String commit) {
        return String.format("%s\0%s\0%s\0%s",
                branch != null ? branch : "",
                isWorktree,
                mainRepo != null ? mainRepo : "",
                commit != null ? commit : "");
    }

    /**
     * Check if the git HEAD has changed since the last check.
     *
     * @param cwd the working directory
     * @param lastDedupKey the previous dedup key (may be null)
     * @return the new dedup key if changed, or empty if unchanged
     */
    public Optional<String> checkHeadChanged(Path cwd, String lastDedupKey) {
        Optional<GitInfo> info = getGitInfo(cwd);
        if (info.isEmpty()) {
            return Optional.empty();
        }

        GitInfo gitInfo = info.get();
        String newKey = gitHeadDedupKey(
                gitInfo.branch(),
                gitInfo.worktreeInfo().isWorktree(),
                gitInfo.worktreeInfo().mainRepo(),
                gitInfo.commit()
        );

        if (newKey.equals(lastDedupKey)) {
            return Optional.empty();
        }

        return Optional.of(newKey);
    }

    private Optional<String> runGitCommand(Path cwd, String... args) {
        try {
            ProcessBuilder pb = new ProcessBuilder();
            pb.command().add("git");
            for (String arg : args) {
                pb.command().add(arg);
            }
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            boolean finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Git command timed out: {}", String.join(" ", args));
                return Optional.empty();
            }

            if (process.exitValue() != 0) {
                log.debug("Git command failed (exit {}): {}", process.exitValue(), output);
                return Optional.empty();
            }

            String result = output.toString().trim();
            return result.isEmpty() ? Optional.empty() : Optional.of(result);

        } catch (IOException | InterruptedException e) {
            log.debug("Git command failed: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }
}
