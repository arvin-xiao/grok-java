package ai.grok.workflow;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The outcome of a workflow execution.
 * Mirrors the Rust WorkflowOutcome from xai-workflow.
 */
public record WorkflowOutcome(
        Status status,
        String summary,
        List<PhaseResult> phaseResults,
        long durationMs,
        int agentCallsUsed
) {
    /**
     * Workflow execution status.
     */
    public enum Status {
        SUCCESS,
        PARTIAL_FAILURE,
        CANCELLED,
        ERROR
    }

    /**
     * Result from a single phase.
     */
    public record PhaseResult(
            String phaseName,
            Status status,
            JsonNode output,
            String error
    ) {
        public static PhaseResult success(String name, JsonNode output) {
            return new PhaseResult(name, Status.SUCCESS, output, null);
        }

        public static PhaseResult failure(String name, String error) {
            return new PhaseResult(name, Status.ERROR, null, error);
        }

        public static PhaseResult cancelled(String name) {
            return new PhaseResult(name, Status.CANCELLED, null, "Phase was cancelled");
        }
    }
}
