package ai.grok.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FileSystemWatcher.
 * Mirrors the Rust xai-fsnotify nested checkout tests (2026-08-03 e5478ef sync).
 */
class FileSystemWatcherTest {

    @Test
    @DisplayName("nested checkout detection works")
    void nestedCheckoutDetection(@TempDir Path tempDir) throws IOException {
        // Create a nested git repo (simulating a worktree or submodule)
        Path nestedRepo = tempDir.resolve("nested");
        Files.createDirectory(nestedRepo);
        Files.createDirectory(nestedRepo.resolve(".git"));

        List<FileSystemWatcher.FileChangeEvent> events = new CopyOnWriteArrayList<>();
        try (FileSystemWatcher watcher = new FileSystemWatcher(tempDir, events::add)) {
            assertTrue(watcher.isNestedCheckout(nestedRepo));
            assertFalse(watcher.isNestedCheckout(tempDir));
            assertEquals(1, watcher.getNestedCheckouts().size());
            assertTrue(watcher.getNestedCheckouts().contains(nestedRepo));
        }
    }

    @Test
    @DisplayName("non-nested directories are not flagged")
    void nonNestedDirectoriesNotFlagged(@TempDir Path tempDir) throws IOException {
        // Create regular directories
        Path regularDir = tempDir.resolve("regular");
        Files.createDirectory(regularDir);

        List<FileSystemWatcher.FileChangeEvent> events = new CopyOnWriteArrayList<>();
        try (FileSystemWatcher watcher = new FileSystemWatcher(tempDir, events::add)) {
            assertFalse(watcher.isNestedCheckout(regularDir));
            assertTrue(watcher.getNestedCheckouts().isEmpty());
        }
    }

    @Test
    @DisplayName("watcher can start and stop")
    void watcherCanStartAndStop(@TempDir Path tempDir) throws IOException {
        List<FileSystemWatcher.FileChangeEvent> events = new CopyOnWriteArrayList<>();
        FileSystemWatcher watcher = new FileSystemWatcher(tempDir, events::add);

        watcher.start();
        assertTrue(watcher.isRunning());

        watcher.stop();
        assertFalse(watcher.isRunning());

        watcher.close(); // Should be safe to call multiple times
    }

    @Test
    @DisplayName("file creation triggers event")
    void fileCreationTriggersEvent(@TempDir Path tempDir) throws IOException, InterruptedException {
        List<FileSystemWatcher.FileChangeEvent> events = new CopyOnWriteArrayList<>();
        FileSystemWatcher watcher = new FileSystemWatcher(tempDir, events::add);

        try {
            watcher.start();
            Thread.sleep(100); // Give watcher time to start

            // Create a file
            Files.writeString(tempDir.resolve("test.txt"), "hello");

            // Wait for event
            Thread.sleep(500);

            // Should have at least one CREATED event
            assertTrue(events.stream().anyMatch(e -> e.type() == FileSystemWatcher.EventType.CREATED));
        } finally {
            watcher.stop();
            Thread.sleep(100); // Give watcher time to release resources
        }
    }

    @Test
    @DisplayName("git HEAD change detection works")
    void gitHeadChangeDetection(@TempDir Path tempDir) throws IOException {
        List<FileSystemWatcher.FileChangeEvent> events = new CopyOnWriteArrayList<>();
        FileSystemWatcher watcher = new FileSystemWatcher(tempDir, events::add);

        // Initial check should not emit event (no git repo)
        watcher.checkGitHeadChanged();
        assertTrue(events.isEmpty());
    }

    private boolean isRunning() {
        // Helper method for test - in real code this would be public
        return false;
    }
}
