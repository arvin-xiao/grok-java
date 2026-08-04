package ai.grok.config;

import java.util.Optional;

/**
 * Workflow engine configuration.
 * Mirrors the Rust workflow config from xai-workflow.
 */
public record WorkflowConfig(
        boolean enabled,
        int defaultAgentBudget,
        Optional<String> defaultScript,
        boolean journalEnabled,
        String journalPath
) {
    public static WorkflowConfig defaults() {
        return new WorkflowConfig(
                false,
                10,
                Optional.empty(),
                true,
                ".grok/journal"
        );
    }
}
