package ai.grok.permission;

/**
 * Gate preflight checks before tool execution.
 * Mirrors the Rust gate_preflight module from xai-grok-workspace.
 *
 * <p>Performs safety checks before a command is allowed to execute,
 * including permission verification and environment validation.
 */
public class GatePreflight {

    private final ExecRiskAssessor riskAssessor;
    private final AutoModePolicy autoMode;

    public GatePreflight(ExecRiskAssessor riskAssessor, AutoModePolicy autoMode) {
        this.riskAssessor = riskAssessor;
        this.autoMode = autoMode;
    }

    /**
     * Run preflight checks for a command.
     *
     * @return the preflight result indicating whether execution may proceed
     */
    public PreflightResult check(String command, ExecRiskAssessor.ShellContext context) {
        // Step 1: Assess risk
        ExecRiskAssessor.ExecRisk risk = riskAssessor.assess(command, context);

        // Step 2: Check auto-mode policy
        AutoModePolicy.Decision decision = autoMode.decide(risk);

        return switch (decision) {
            case AutoModePolicy.Decision.Allow -> new PreflightResult(PreflightStatus.APPROVED, risk, "Auto-approved");
            case AutoModePolicy.Decision.Deny ->
                    new PreflightResult(PreflightStatus.DENIED, risk, "Auto-denied by policy: " + risk.reason());
            case AutoModePolicy.Decision.AskUser -> new PreflightResult(PreflightStatus.NEEDS_APPROVAL, risk,
                    "Requires user approval: " + risk.reason());
        };
    }

    /**
     * Preflight status.
     */
    public enum PreflightStatus {APPROVED, DENIED, NEEDS_APPROVAL}

    /**
     * Preflight result.
     */
    public record PreflightResult(
            PreflightStatus status,
            ExecRiskAssessor.ExecRisk risk,
            String message
    ) {
        public boolean canProceed() {
            return status == PreflightStatus.APPROVED;
        }
    }
}
