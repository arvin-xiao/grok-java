package ai.grok.workflow;

import java.util.List;

/**
 * Metadata describing a workflow definition.
 * Mirrors the Rust WorkflowMeta from xai-workflow.
 */
public record WorkflowMeta(
        String name,
        String description,
        String whenToUse,
        List<PhaseMeta> phases
) {
    /**
     * Metadata for a single phase within a workflow.
     */
    public record PhaseMeta(
            String name,
            String description,
            String agentType,
            boolean parallel
    ) {
    }
}
