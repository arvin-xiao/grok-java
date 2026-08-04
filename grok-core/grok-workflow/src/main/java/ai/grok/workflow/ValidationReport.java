package ai.grok.workflow;

import java.util.List;

/**
 * Result of validating a workflow script.
 * Mirrors the Rust ValidationReport from xai-workflow.
 */
public record ValidationReport(
        boolean valid,
        List<ValidationIssue> issues,
        WorkflowMeta meta
) {
    public static ValidationReport ok(WorkflowMeta meta) {
        return new ValidationReport(true, List.of(), meta);
    }

    public static ValidationReport withErrors(List<ValidationIssue> issues) {
        return new ValidationReport(false, issues, null);
    }

    /**
     * A single validation issue.
     */
    public record ValidationIssue(
            Severity severity,
            int line,
            int column,
            String message
    ) {
        public static ValidationIssue error(int line, int col, String msg) {
            return new ValidationIssue(Severity.ERROR, line, col, msg);
        }

        public static ValidationIssue warning(int line, int col, String msg) {
            return new ValidationIssue(Severity.WARNING, line, col, msg);
        }

        public enum Severity {ERROR, WARNING, INFO}
    }
}
