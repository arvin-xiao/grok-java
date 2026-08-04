package ai.grok.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Assesses the execution risk of Bash commands before they run.
 * Mirrors the Rust exec_risk module from xai-grok-workspace.
 *
 * <p>Analyzes command argv flags that spawn programs, and ambient
 * local/worktree git config to determine risk level.
 */
public class ExecRiskAssessor {

    /**
     * Programs that are considered high-risk (can modify system state).
     */
    private static final Set<String> HIGH_RISK_PROGRAMS = Set.of(
            "rm", "format", "mkfs", "dd", "fdisk", "parted",
            "chmod", "chown", "chgrp",
            "shutdown", "reboot", "halt", "poweroff",
            "curl", "wget", "nc", "ncat", "netcat",
            "sudo", "su", "doas"
    );

    /**
     * Programs that are considered medium-risk.
     */
    private static final Set<String> MEDIUM_RISK_PROGRAMS = Set.of(
            "git", "npm", "yarn", "pnpm", "pip", "cargo", "mvn", "gradle",
            "docker", "podman", "kubectl",
            "python", "python3", "node", "ruby", "perl",
            "apt", "apt-get", "yum", "dnf", "brew", "pacman"
    );

    /**
     * Git subcommands that are read-only queries (safe to auto-approve).
     */
    private static final Set<String> GIT_READ_ONLY_VERBS = Set.of(
            "status", "diff", "log", "branch", "tag", "remote",
            "show", "blame", "grep", "ls-files", "rev-parse",
            "describe", "merge-base", "config"
    );

    /**
     * Git options that are unsafe even with read-only verbs (can write/execute).
     */
    private static final Set<String> GIT_UNSAFE_OPTIONS = Set.of(
            "-O", "--open-files-in-pager",
            "--edit", "-e",
            "--interactive", "-i",
            "--patch", "-p"
    );

    /**
     * Dangerous patterns that should always be blocked.
     */
    private static final List<Pattern> BLOCKED_PATTERNS = List.of(
            Pattern.compile("rm\\s+-rf\\s+/"),           // rm -rf /
            Pattern.compile("mkfs\\."),                    // filesystem format
            Pattern.compile(":\\(\\)\\s*\\{\\s*:\\|:\\s*&\\s*\\}\\s*;"), // fork bomb
            Pattern.compile(">\\s*/dev/sd[a-z]"),          // raw disk write
            Pattern.compile("chmod\\s+-R\\s+777\\s+/")     // recursive world-writable on /
    );

    /**
     * Check if git words form a read-only query.
     * Mirrors the Rust `exec_risk::git_words_are_read_only_query` helper.
     * A git command is read-only if:
     * 1. The subcommand is a known read-only verb
     * 2. No unsafe options are present (e.g. -O, --edit, --interactive)
     */
    public static boolean gitWordsAreReadOnlyQuery(ShellContext context) {
        if (context == null || context.subCommand() == null) {
            return false; // Can't determine, assume may write
        }
        String subCmd = context.subCommand().toLowerCase();
        if (!GIT_READ_ONLY_VERBS.contains(subCmd)) {
            return false;
        }
        // Check for unsafe options in the full command
        String fullCommand = context.fullCommand();
        if (fullCommand != null) {
            for (String unsafe : GIT_UNSAFE_OPTIONS) {
                if (fullCommand.contains(unsafe)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Check if git words contain an unsafe query option.
     * Mirrors the Rust `exec_risk::git_words_have_unsafe_query_option` helper.
     */
    public static boolean gitWordsHaveUnsafeQueryOption(String command) {
        if (command == null) return false;
        for (String unsafe : GIT_UNSAFE_OPTIONS) {
            if (command.contains(unsafe)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Assess the execution risk of a command.
     */
    public ExecRisk assess(String command, ShellContext context) {
        if (command == null || command.isBlank()) {
            return new ExecRisk(RiskLevel.SAFE, "Empty command", List.of(), false);
        }

        // Check for blocked patterns first
        for (Pattern p : BLOCKED_PATTERNS) {
            if (p.matcher(command).find()) {
                return new ExecRisk(RiskLevel.BLOCKED,
                        "Command matches blocked pattern: " + p.pattern(),
                        List.of(), true);
            }
        }

        // Parse command into tokens
        List<String> tokens = tokenize(command);
        if (tokens.isEmpty()) {
            return new ExecRisk(RiskLevel.SAFE, "No commands detected", List.of(), false);
        }

        // Analyze each command in a pipeline/chain
        List<String> detectedPrograms = new ArrayList<>();
        RiskLevel maxRisk = RiskLevel.SAFE;
        StringBuilder reason = new StringBuilder();

        for (String program : extractPrograms(tokens)) {
            detectedPrograms.add(program);
            RiskLevel risk = classifyProgram(program, context);
            if (risk.ordinal() > maxRisk.ordinal()) {
                maxRisk = risk;
                reason.append("Program '").append(program).append("' classified as ").append(risk).append(". ");
            }
        }

        boolean requiresApproval = maxRisk.ordinal() >= RiskLevel.MEDIUM.ordinal();
        return new ExecRisk(maxRisk, reason.toString().trim(), detectedPrograms, requiresApproval);
    }

    private RiskLevel classifyProgram(String program, ShellContext context) {
        String normalized = normalizeProgramName(program);

        // Git read-only commands are safe (shared helper, mirrors Rust exec_risk)
        if ("git".equals(normalized) && gitWordsAreReadOnlyQuery(context)) {
            return RiskLevel.SAFE;
        }

        if (HIGH_RISK_PROGRAMS.contains(normalized)) {
            return RiskLevel.HIGH;
        }
        if (MEDIUM_RISK_PROGRAMS.contains(normalized)) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private String normalizeProgramName(String program) {
        // Extract basename, strip .exe suffix
        String base = program;
        int lastSlash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (lastSlash >= 0) base = base.substring(lastSlash + 1);
        if (base.toLowerCase().endsWith(".exe")) base = base.substring(0, base.length() - 4);
        return base.toLowerCase();
    }


    private List<String> extractPrograms(List<String> tokens) {
        List<String> programs = new ArrayList<>();
        boolean expectProgram = true;

        for (String token : tokens) {
            // Skip shell operators
            if (token.matches("[|;&]+") || token.equals("&&") || token.equals("||")) {
                expectProgram = true;
                continue;
            }
            // Skip redirections
            if (token.startsWith(">") || token.startsWith("<") || token.equals("2>&1")) {
                continue;
            }
            // Skip flags
            if (token.startsWith("-")) {
                continue;
            }
            if (expectProgram && !token.isEmpty()) {
                programs.add(token);
                expectProgram = false;
            }
        }
        return programs;
    }

    private List<String> tokenize(String command) {
        // Simple tokenization - split on whitespace, respecting quotes
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (inQuote) {
                if (c == quoteChar) {
                    inQuote = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quoteChar = c;
            } else if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /**
     * Risk level enumeration.
     */
    public enum RiskLevel {SAFE, LOW, MEDIUM, HIGH, BLOCKED}

    /**
     * Risk assessment result.
     */
    public record ExecRisk(
            RiskLevel level,
            String reason,
            List<String> detectedPrograms,
            boolean requiresApproval
    ) {
    }

    /**
     * Shell context for risk assessment.
     */
    public record ShellContext(
            String workingDirectory,
            String subCommand,
            String fullCommand,
            Map<String, String> environment
    ) {
        public static ShellContext of(String cwd) {
            return new ShellContext(cwd, null, null, Map.of());
        }

        public static ShellContext of(String cwd, String subCommand) {
            return new ShellContext(cwd, subCommand, null, Map.of());
        }

        public static ShellContext of(String cwd, String subCommand, String fullCommand) {
            return new ShellContext(cwd, subCommand, fullCommand, Map.of());
        }
    }
}
