package ai.grok.permission;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Path context for permission requests.
 * Mirrors the Rust `RequestPathContext` (renamed from `EditPathContext` in 2026-08-03 sync).
 *
 * <p>The requesting session's execution cwd for one permission request. Shared
 * parent/subagent managers serve sessions whose cwd differs from the manager's,
 * so path rules and edit-target resolution must anchor to where the requesting
 * tool actually resolves paths, not where the manager lives.
 */
public record RequestPathContext(
        /** The real current working directory of the requesting session. */
        Path realCwd,
        /** Optional display cwd (may differ from real cwd in virtual environments). */
        Optional<Path> displayCwd
) {
    /**
     * Create a context with only a real cwd (no display cwd).
     */
    public static RequestPathContext of(Path realCwd) {
        return new RequestPathContext(realCwd, Optional.empty());
    }

    /**
     * Create a context with both real and display cwd.
     */
    public static RequestPathContext of(Path realCwd, Path displayCwd) {
        return new RequestPathContext(realCwd, Optional.ofNullable(displayCwd));
    }

    /**
     * Resolve a relative path against the real cwd.
     * If the path is already absolute, return it as-is.
     */
    public Path resolve(Path path) {
        if (path.isAbsolute()) {
            return path;
        }
        return realCwd.resolve(path);
    }

    /**
     * Resolve a relative path string against the real cwd.
     */
    public Path resolve(String path) {
        return resolve(Path.of(path));
    }

    /**
     * Get the effective cwd for rule matching.
     * Returns the real cwd, which is used for anchoring relative path rules.
     */
    public Path ruleCwd() {
        return realCwd;
    }

    /**
     * Get the cwd for display purposes.
     * Falls back to real cwd if no display cwd is set.
     */
    public Path effectiveDisplayCwd() {
        return displayCwd.orElse(realCwd);
    }
}
