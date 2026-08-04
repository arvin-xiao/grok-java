package ai.grok.permission;

/**
 * Auto-mode policy for automatic approval/denial of tool executions.
 * Mirrors the Rust auto_mode module from xai-grok-workspace.
 *
 * <p>Determines whether a command should be auto-approved, auto-denied,
 * or requires user confirmation based on risk level and policy configuration.
 */
public class AutoModePolicy {

    private final Preset preset;

    public AutoModePolicy(Preset preset) {
        this.preset = preset;
    }

    public static AutoModePolicy balanced() {
        return new AutoModePolicy(Preset.BALANCED);
    }

    public static AutoModePolicy permissive() {
        return new AutoModePolicy(Preset.PERMISSIVE);
    }

    public static AutoModePolicy strict() {
        return new AutoModePolicy(Preset.STRICT);
    }

    /**
     * Decide whether to allow, deny, or ask user for a given risk assessment.
     */
    public Decision decide(ExecRiskAssessor.ExecRisk risk) {
        return switch (preset) {
            case PERMISSIVE -> {
                if (risk.level() == ExecRiskAssessor.RiskLevel.BLOCKED) yield Decision.Deny;
                yield Decision.Allow;
            }
            case BALANCED -> switch (risk.level()) {
                case SAFE, LOW -> Decision.Allow;
                case MEDIUM -> Decision.AskUser;
                case HIGH, BLOCKED -> Decision.Deny;
            };
            case STRICT -> switch (risk.level()) {
                case SAFE -> Decision.Allow;
                case LOW, MEDIUM, HIGH -> Decision.AskUser;
                case BLOCKED -> Decision.Deny;
            };
            case LOCKDOWN -> switch (risk.level()) {
                case SAFE -> Decision.Allow;
                case LOW, MEDIUM, HIGH, BLOCKED -> Decision.Deny;
            };
        };
    }

    public Preset preset() {
        return preset;
    }

    /**
     * The three possible decisions.
     */
    public enum Decision {Allow, Deny, AskUser}

    /**
     * Predefined policy presets.
     */
    public enum Preset {
        /**
         * Approve everything (for trusted environments).
         */
        PERMISSIVE,
        /**
         * Approve safe/low, ask for medium, deny high/blocked.
         */
        BALANCED,
        /**
         * Ask user for anything above safe.
         */
        STRICT,
        /**
         * Deny everything above safe.
         */
        LOCKDOWN
    }
}
