package ai.grok.session;

import ai.grok.session.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default session implementation. Manages conversation history and drives the agent loop.
 * Supports prompt queue merging: if multiple prompts arrive while a turn is running,
 * they are coalesced before processing (mirrors Rust prompt queue merging).
 */
public class DefaultSession implements Session {
    private static final Logger log = LoggerFactory.getLogger(DefaultSession.class);

    private final String id;
    private final Agent agent;
    private final List<ChatMessage> history = new ArrayList<>();
    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.IDLE);
    private final BlockingQueue<String> promptQueue = new LinkedBlockingQueue<>();
    private int totalTokens = 0;
    private int totalTurns = 0;

    public DefaultSession(String id, Agent agent) {
        this.id = id;
        this.agent = agent;
        // Add system prompt as first message
        history.add(new ChatMessage.System(agent.systemPrompt()));
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public SessionState state() {
        return state.get();
    }

    @Override
    public CompletableFuture<PromptResult> prompt(String content) {
        return prompt(content, null);
    }

    @Override
    public CompletableFuture<PromptResult> prompt(String content, AgentEventSink sink) {
        // If a turn is already running, queue the prompt for merging
        if (state.get() == SessionState.TURN_RUNNING) {
            promptQueue.offer(content);
            log.info("Prompt queued for merging (queue size: {})", promptQueue.size());
            return CompletableFuture.completedFuture(
                    new PromptResult("[queued]", totalTurns, totalTokens));
        }

        return CompletableFuture.supplyAsync(() -> {
            state.set(SessionState.TURN_RUNNING);
            try {
                // Merge any queued prompts with the current content
                String mergedContent = mergePrompts(content);

                // Add user message
                history.add(new ChatMessage.User(mergedContent));
                totalTurns++;

                // Execute agent turn
                var request = new AgentRequest(id, List.copyOf(history));
                AgentResponse response;

                if (sink != null) {
                    agent.streamTurn(request, sink);
                    response = agent.turn(request);
                } else {
                    response = agent.turn(request);
                }

                // Add assistant response to history
                if (response.textContent() != null && !response.textContent().isEmpty()) {
                    history.add(new ChatMessage.Assistant(response.textContent()));
                }

                totalTokens += response.promptTokens() + response.completionTokens();

                state.set(SessionState.IDLE);
                return new PromptResult(response.textContent(), totalTurns, totalTokens);

            } catch (Exception e) {
                log.error("Session prompt failed", e);
                state.set(SessionState.IDLE);
                if (sink != null) sink.onError(e);
                throw new CompletionException(e);
            }
        }, Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("session-" + id).factory()));
    }

    /**
     * Merge the primary prompt with any queued prompts.
     * This implements the "prompt queue merging" feature from the Rust codebase.
     */
    private String mergePrompts(String primary) {
        List<String> queued = new ArrayList<>();
        promptQueue.drainTo(queued);

        if (queued.isEmpty()) {
            return primary;
        }

        StringBuilder merged = new StringBuilder(primary);
        for (String extra : queued) {
            merged.append("\n\n[Additional prompt]: ").append(extra);
        }
        log.info("Merged {} queued prompts into current turn", queued.size());
        return merged.toString();
    }

    @Override
    public void cancel() {
        state.set(SessionState.TURN_CANCELLING);
        log.info("Session {} cancelled", id);
    }

    @Override
    public List<ChatMessage> history() {
        return List.copyOf(history);
    }

    @Override
    public void close() {
        state.set(SessionState.IDLE);
        log.info("Session {} closed", id);
    }
}
