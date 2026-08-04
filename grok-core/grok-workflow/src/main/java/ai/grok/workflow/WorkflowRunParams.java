package ai.grok.workflow;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Parameters for running a workflow.
 * Mirrors the Rust WorkflowRunParams from xai-workflow.
 */
public record WorkflowRunParams(
        String script,
        JsonNode args,
        Journal journal,
        int agentBudget,
        CancellationToken cancel
) {
    public WorkflowRunParams {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("script must not be blank");
        }
        if (agentBudget <= 0) {
            agentBudget = 10; // default budget
        }
        if (cancel == null) {
            cancel = new CancellationToken();
        }
    }

    /**
     * Builder for WorkflowRunParams.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String script;
        private JsonNode args;
        private Journal journal;
        private int agentBudget = 10;
        private CancellationToken cancel;

        public Builder script(String script) {
            this.script = script;
            return this;
        }

        public Builder args(JsonNode args) {
            this.args = args;
            return this;
        }

        public Builder journal(Journal journal) {
            this.journal = journal;
            return this;
        }

        public Builder agentBudget(int budget) {
            this.agentBudget = budget;
            return this;
        }

        public Builder cancel(CancellationToken cancel) {
            this.cancel = cancel;
            return this;
        }

        public WorkflowRunParams build() {
            return new WorkflowRunParams(script, args, journal, agentBudget, cancel);
        }
    }

    /**
     * Cancellation token for cooperative cancellation.
     */
    public static class CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        public void cancel() {
            cancelled.set(true);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public void throwIfCancelled() {
            if (cancelled.get()) {
                throw new WorkflowCancelledException("Workflow was cancelled");
            }
        }
    }

    /**
     * Exception thrown when a workflow is cancelled.
     */
    public static class WorkflowCancelledException extends RuntimeException {
        public WorkflowCancelledException(String message) {
            super(message);
        }
    }
}
