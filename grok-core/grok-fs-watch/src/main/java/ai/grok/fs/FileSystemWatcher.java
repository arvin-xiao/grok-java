package ai.grok.fs;

import ai.grok.git.GitHeadDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * File system watcher using Java NIO WatchService.
 * Mirrors the Rust `xai-fsnotify` crate (2026-08-03 e5478ef sync).
 *
 * <p>Features:
 * <ul>
 *   <li>Watches directories for file changes</li>
 *   <li>Skips nested checkouts (in-repo worktrees) to avoid stalling startup</li>
 *   <li>Integrates with GitHeadDetector for HEAD change detection</li>
 *   <li>Debounced event notification</li>
 * </ul>
 */
public final class FileSystemWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FileSystemWatcher.class);

    private final Path watchRoot;
    private final WatchService watchService;
    private final Consumer<FileChangeEvent> eventHandler;
    private final GitHeadDetector gitDetector;
    private final Set<Path> nestedCheckouts = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> lastGitDedupKey = new AtomicReference<>();
    private Thread watchThread;

    /**
     * Event types for file system changes.
     */
    public enum EventType {
        CREATED, MODIFIED, DELETED, GIT_HEAD_CHANGED
    }

    /**
     * A file system change event.
     */
    public record FileChangeEvent(EventType type, Path path) {}

    /**
     * Create a new file system watcher.
     *
     * @param watchRoot the root directory to watch
     * @param eventHandler callback for file change events
     * @throws IOException if the watch service cannot be created
     */
    public FileSystemWatcher(Path watchRoot, Consumer<FileChangeEvent> eventHandler) throws IOException {
        this.watchRoot = watchRoot;
        this.eventHandler = eventHandler;
        this.gitDetector = new GitHeadDetector();
        this.watchService = FileSystems.getDefault().newWatchService();
    }

    /**
     * Start watching the directory.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            watchThread = Thread.ofVirtual().name("fs-watcher-" + watchRoot).start(this::watchLoop);
            log.info("File system watcher started for: {}", watchRoot);
        }
    }

    /**
     * Check if the watcher is currently running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Stop watching.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (watchThread != null) {
                watchThread.interrupt();
            }
            try {
                watchService.close();
            } catch (IOException e) {
                log.warn("Error closing watch service", e);
            }
            // Wait for thread to fully finish to release directory locks
            if (watchThread != null) {
                try {
                    watchThread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            log.info("File system watcher stopped for: {}", watchRoot);
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Check if a path is a nested checkout (in-repo worktree).
     * Nested checkouts are skipped to avoid stalling startup.
     *
     * @param path the path to check
     * @return true if this is a nested checkout that should be skipped
     */
    public boolean isNestedCheckout(Path path) {
        if (nestedCheckouts.contains(path)) {
            return true;
        }

        // Check if this directory contains a .git file/directory
        Path gitPath = path.resolve(".git");
        if (Files.exists(gitPath) && !path.equals(watchRoot)) {
            // This is a nested git repo (worktree or submodule)
            nestedCheckouts.add(path);
            log.debug("Detected nested checkout at: {}", path);
            return true;
        }

        return false;
    }

    /**
     * Get the set of detected nested checkouts.
     */
    public Set<Path> getNestedCheckouts() {
        return Set.copyOf(nestedCheckouts);
    }

    /**
     * Check if git HEAD has changed and emit event if so.
     */
    public void checkGitHeadChanged() {
        var changed = gitDetector.checkHeadChanged(watchRoot, lastGitDedupKey.get());
        if (changed.isPresent()) {
            lastGitDedupKey.set(changed.get());
            eventHandler.accept(new FileChangeEvent(EventType.GIT_HEAD_CHANGED, watchRoot));
            log.debug("Git HEAD changed: {}", changed.get());
        }
    }

    private void watchLoop() {
        // Register the root directory
        try {
            registerDirectory(watchRoot);
        } catch (IOException e) {
            log.error("Failed to register watch root: {}", watchRoot, e);
            running.set(false);
            return;
        }

        // Initial git HEAD check
        checkGitHeadChanged();

        while (running.get()) {
            try {
                WatchKey key = watchService.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (key == null) {
                    // Periodically check git HEAD
                    checkGitHeadChanged();
                    continue;
                }

                Path dir = (Path) key.watchable();

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        log.warn("Watch event overflow for: {}", dir);
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path name = pathEvent.context();
                    Path child = dir.resolve(name);

                    // Skip nested checkouts
                    if (Files.isDirectory(child) && isNestedCheckout(child)) {
                        log.debug("Skipping nested checkout: {}", child);
                        continue;
                    }

                    EventType eventType = switch (kind.name()) {
                        case "ENTRY_CREATE" -> EventType.CREATED;
                        case "ENTRY_MODIFY" -> EventType.MODIFIED;
                        case "ENTRY_DELETE" -> EventType.DELETED;
                        default -> null;
                    };

                    if (eventType != null) {
                        eventHandler.accept(new FileChangeEvent(eventType, child));
                        log.debug("File event: {} {}", eventType, child);
                    }

                    // Register new directories
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
                        if (!isNestedCheckout(child)) {
                            try {
                                registerDirectory(child);
                            } catch (IOException e) {
                                log.warn("Failed to register new directory: {}", child, e);
                            }
                        }
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    log.debug("Watch key no longer valid for: {}", dir);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }
        }
    }

    private void registerDirectory(Path dir) throws IOException {
        dir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
    }
}
