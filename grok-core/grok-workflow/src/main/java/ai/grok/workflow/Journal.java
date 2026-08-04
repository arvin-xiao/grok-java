package ai.grok.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Workflow journal for checkpoint/replay support.
 * Mirrors the Rust Journal from xai-workflow.
 *
 * <p>Records each step's output so that a workflow can be resumed
 * from the last successful checkpoint without re-executing prior steps.
 * This enables idempotent execution and断点恢复 (breakpoint recovery).
 */
public class Journal {

    private final ConcurrentSkipListMap<Long, JournalEntry> entries = new ConcurrentSkipListMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Load a journal from a file.
     */
    public static Journal load(Path path) throws IOException {
        Journal journal = new Journal();
        if (!Files.exists(path)) {
            return journal;
        }
        String content = Files.readString(path);
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode array = (ArrayNode) mapper.readTree(content);
        for (JsonNode node : array) {
            long seq = node.get("seq").asLong();
            String kind = node.get("kind").asText();
            String hash = node.get("hash").asText();
            JsonNode value = node.get("value");
            journal.record(seq, kind, hash, value);
        }
        return journal;
    }

    /**
     * Record a journal entry.
     *
     * @param seq   sequence number (monotonically increasing)
     * @param kind  the kind of step (e.g. "agent_call", "tool_exec", "phase_complete")
     * @param hash  content hash for deduplication / integrity
     * @param value the structured output value
     */
    public void record(long seq, String kind, String hash, JsonNode value) {
        entries.put(seq, new JournalEntry(seq, kind, hash, value));
    }

    /**
     * Check if the journal covers a given sequence number.
     */
    public boolean covers(long seq) {
        return entries.containsKey(seq);
    }

    /**
     * Get the entry for a given sequence number.
     */
    public Optional<JournalEntry> get(long seq) {
        return Optional.ofNullable(entries.get(seq));
    }

    /**
     * Get the highest sequence number recorded.
     */
    public long highestSeq() {
        return entries.isEmpty() ? 0 : entries.lastKey();
    }

    /**
     * Get all entries in order.
     */
    public List<JournalEntry> entries() {
        return List.copyOf(entries.values());
    }

    /**
     * Save the journal to a file.
     */
    public void save(Path path) throws IOException {
        ArrayNode array = mapper.createArrayNode();
        for (JournalEntry entry : entries.values()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("seq", entry.seq());
            node.put("kind", entry.kind());
            node.put("hash", entry.hash());
            node.set("value", entry.value());
            array.add(node);
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(array));
    }

    /**
     * A single journal entry.
     */
    public record JournalEntry(
            long seq,
            String kind,
            String hash,
            JsonNode value
    ) {
    }
}
