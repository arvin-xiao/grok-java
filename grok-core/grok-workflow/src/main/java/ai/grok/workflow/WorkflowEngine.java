package ai.grok.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Workflow execution engine.
 * Mirrors the Rust xai-workflow module — supports multi-phase workflow scripts
 * with journal-based checkpoint/replay and agent budget control.
 *
 * <p>In the Rust version this is backed by a Rhai scripting engine.
 * In Java we use a simplified JSON-based workflow definition that can
 * be extended later with GraalVM Polyglot for full scripting support.
 */
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Run a workflow with the given parameters.
     */
    public WorkflowOutcome run(WorkflowRunParams params) {
        long startTime = System.currentTimeMillis();
        List<WorkflowOutcome.PhaseResult> phaseResults = new ArrayList<>();
        int agentCallsUsed = 0;

        try {
            // Parse the workflow script (JSON format)
            JsonNode workflowDef = mapper.readTree(params.script());
            JsonNode phasesNode = workflowDef.get("phases");

            if (phasesNode == null || !phasesNode.isArray()) {
                return new WorkflowOutcome(
                        WorkflowOutcome.Status.ERROR,
                        "Invalid workflow: missing or invalid 'phases' array",
                        List.of(),
                        System.currentTimeMillis() - startTime,
                        0
                );
            }

            Journal journal = params.journal();
            if (journal == null) {
                journal = new Journal();
            }

            // Execute each phase
            for (int i = 0; i < phasesNode.size(); i++) {
                params.cancel().throwIfCancelled();

                // Check budget
                if (agentCallsUsed >= params.agentBudget()) {
                    phaseResults.add(WorkflowOutcome.PhaseResult.failure(
                            "phase-" + i, "Agent budget exhausted (" + params.agentBudget() + ")"
                    ));
                    break;
                }

                JsonNode phaseDef = phasesNode.get(i);
                String phaseName = phaseDef.has("name") ? phaseDef.get("name").asText() : "phase-" + i;

                // Check journal for replay
                if (journal.covers(i)) {
                    Journal.JournalEntry entry = journal.get(i).orElseThrow();
                    log.info("Replaying phase '{}' from journal (seq={})", phaseName, i);
                    phaseResults.add(WorkflowOutcome.PhaseResult.success(phaseName, entry.value()));
                    continue;
                }

                // Execute the phase
                log.info("Executing workflow phase: {} (budget remaining: {})",
                        phaseName, params.agentBudget() - agentCallsUsed);

                try {
                    JsonNode result = executePhase(phaseDef, params.args());
                    phaseResults.add(WorkflowOutcome.PhaseResult.success(phaseName, result));

                    // Record in journal
                    String hash = Integer.toHexString(result.toString().hashCode());
                    journal.record(i, "phase_complete", hash, result);
                    agentCallsUsed++;
                } catch (Exception e) {
                    log.error("Phase '{}' failed: {}", phaseName, e.getMessage());
                    phaseResults.add(WorkflowOutcome.PhaseResult.failure(phaseName, e.getMessage()));
                    break;
                }
            }

            WorkflowOutcome.Status status = determineStatus(phaseResults, params.cancel());
            String summary = buildSummary(phaseResults);

            return new WorkflowOutcome(
                    status, summary, phaseResults,
                    System.currentTimeMillis() - startTime,
                    agentCallsUsed
            );

        } catch (WorkflowRunParams.WorkflowCancelledException e) {
            return new WorkflowOutcome(
                    WorkflowOutcome.Status.CANCELLED, "Workflow cancelled",
                    phaseResults,
                    System.currentTimeMillis() - startTime,
                    agentCallsUsed
            );
        } catch (Exception e) {
            return new WorkflowOutcome(
                    WorkflowOutcome.Status.ERROR, "Workflow error: " + e.getMessage(),
                    phaseResults,
                    System.currentTimeMillis() - startTime,
                    agentCallsUsed
            );
        }
    }

    /**
     * Validate a workflow script without executing it.
     */
    public ValidationReport validate(String script) {
        try {
            JsonNode def = mapper.readTree(script);

            List<ValidationReport.ValidationIssue> issues = new ArrayList<>();

            if (!def.has("phases")) {
                issues.add(ValidationReport.ValidationIssue.error(0, 0, "Missing 'phases' array"));
            } else if (!def.get("phases").isArray()) {
                issues.add(ValidationReport.ValidationIssue.error(0, 0, "'phases' must be an array"));
            } else {
                JsonNode phases = def.get("phases");
                for (int i = 0; i < phases.size(); i++) {
                    JsonNode phase = phases.get(i);
                    if (!phase.has("name")) {
                        issues.add(ValidationReport.ValidationIssue.warning(i, 0,
                                "Phase " + i + " missing 'name'"));
                    }
                    if (!phase.has("action") && !phase.has("agent")) {
                        issues.add(ValidationReport.ValidationIssue.error(i, 0,
                                "Phase " + i + " must have 'action' or 'agent'"));
                    }
                }
            }

            if (issues.stream().anyMatch(i -> i.severity() == ValidationReport.ValidationIssue.Severity.ERROR)) {
                return ValidationReport.withErrors(issues);
            }

            // Extract metadata
            String name = def.has("name") ? def.get("name").asText() : "unnamed";
            String desc = def.has("description") ? def.get("description").asText() : "";
            String whenToUse = def.has("when_to_use") ? def.get("when_to_use").asText() : "";

            List<WorkflowMeta.PhaseMeta> phaseMetas = new ArrayList<>();
            JsonNode phases = def.get("phases");
            for (JsonNode phase : phases) {
                phaseMetas.add(new WorkflowMeta.PhaseMeta(
                        phase.has("name") ? phase.get("name").asText() : "unnamed",
                        phase.has("description") ? phase.get("description").asText() : "",
                        phase.has("agent") ? phase.get("agent").asText() : "default",
                        phase.has("parallel") && phase.get("parallel").asBoolean()
                ));
            }

            WorkflowMeta meta = new WorkflowMeta(name, desc, whenToUse, phaseMetas);
            return new ValidationReport(true, issues, meta);

        } catch (Exception e) {
            return ValidationReport.withErrors(List.of(
                    ValidationReport.ValidationIssue.error(0, 0, "Parse error: " + e.getMessage())
            ));
        }
    }

    private JsonNode executePhase(JsonNode phaseDef, JsonNode args) {
        // Simulated phase execution — in a real implementation this would
        // dispatch to an agent or execute a tool action.
        ObjectNode result = mapper.createObjectNode();
        result.put("phase", phaseDef.has("name") ? phaseDef.get("name").asText() : "unknown");
        result.put("status", "completed");
        result.put("timestamp", System.currentTimeMillis());
        if (args != null) {
            result.set("args", args);
        }
        return result;
    }

    private WorkflowOutcome.Status determineStatus(
            List<WorkflowOutcome.PhaseResult> results,
            WorkflowRunParams.CancellationToken cancel) {
        if (cancel.isCancelled()) return WorkflowOutcome.Status.CANCELLED;
        boolean anyFailed = results.stream()
                .anyMatch(r -> r.status() == WorkflowOutcome.Status.ERROR);
        boolean allSuccess = results.stream()
                .allMatch(r -> r.status() == WorkflowOutcome.Status.SUCCESS);
        if (allSuccess) return WorkflowOutcome.Status.SUCCESS;
        if (anyFailed) return WorkflowOutcome.Status.PARTIAL_FAILURE;
        return WorkflowOutcome.Status.SUCCESS;
    }

    private String buildSummary(List<WorkflowOutcome.PhaseResult> results) {
        long success = results.stream().filter(r -> r.status() == WorkflowOutcome.Status.SUCCESS).count();
        long failed = results.stream().filter(r -> r.status() == WorkflowOutcome.Status.ERROR).count();
        return String.format("Completed %d/%d phases (%d failed)", success, results.size(), failed);
    }
}
