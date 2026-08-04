package ai.grok.permission;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tracks the current permission state for a session.
 * Mirrors the Rust PermissionState from xai-grok-workspace.
 *
 * <p>Manages allowed/denied commands, bash globs, web fetch domains,
 * and MCP tools/servers for the current session.
 */
public class PermissionState {

    private final Set<String> allowedBashCommands;
    private final Set<String> disallowedBashCommands;
    /**
     * Glob patterns authored via the "Always allow" pattern editor.
     * Matched with glob semantics, unlike the literal-prefix allowedBashCommands.
     * Mirrors the Rust `allowed_bash_globs` field.
     */
    private final Set<String> allowedBashGlobs;
    private final Set<String> allowedWebFetchDomains;
    private final Set<String> allowedMcpTools;
    private final Set<String> allowedMcpServers;
    private boolean allowBashExecute;

    public PermissionState() {
        this.allowBashExecute = false;
        this.allowedBashCommands = new HashSet<>();
        this.disallowedBashCommands = new HashSet<>();
        this.allowedBashGlobs = new HashSet<>();
        this.allowedWebFetchDomains = new HashSet<>();
        this.allowedMcpTools = new HashSet<>();
        this.allowedMcpServers = new HashSet<>();
    }

    // ─── Bash commands ──────────────────────────────────────────

    /**
     * Simple glob matching: * matches any sequence of characters.
     * Mirrors the Rust glob matching for permission patterns.
     */
    private static boolean globMatches(String pattern, String text) {
        // Convert glob to regex
        StringBuilder regex = new StringBuilder("^");
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                case '.' -> regex.append("\\.");
                case '\\' -> regex.append("\\\\");
                case '^' -> regex.append("\\^");
                case '$' -> regex.append("\\$");
                case '|' -> regex.append("\\|");
                case '(' -> regex.append("\\(");
                case ')' -> regex.append("\\)");
                case '[' -> regex.append("\\[");
                case ']' -> regex.append("\\]");
                case '{' -> regex.append("\\{");
                case '}' -> regex.append("\\}");
                case '+' -> regex.append("\\+");
                default -> regex.append(c);
            }
        }
        regex.append("$");
        return Pattern.matches(regex.toString(), text);
    }

    public boolean isAllowBashExecute() {
        return allowBashExecute;
    }

    public void setAllowBashExecute(boolean allow) {
        this.allowBashExecute = allow;
    }

    public void allowBashCommand(String command) {
        allowedBashCommands.add(command);
    }

    public void disallowBashCommand(String command) {
        disallowedBashCommands.add(command);
    }

    public boolean isBashCommandAllowed(String command) {
        return allowedBashCommands.contains(command);
    }

    // ─── Bash globs (new in 2026-08-03 sync) ────────────────────

    public boolean isBashCommandDisallowed(String command) {
        return disallowedBashCommands.contains(command);
    }

    /**
     * Add a glob pattern for bash command matching.
     * Unlike literal command prefixes, these are matched with glob semantics.
     */
    public void allowBashGlob(String pattern) {
        allowedBashGlobs.add(pattern);
    }

    public Set<String> getAllowedBashGlobs() {
        return Collections.unmodifiableSet(allowedBashGlobs);
    }

    /**
     * Check if a command matches any allowed glob pattern.
     * Uses simple glob matching (* matches any sequence of characters).
     */
    public boolean isBashCommandMatchedByGlob(String command) {
        for (String pattern : allowedBashGlobs) {
            if (globMatches(pattern, command)) {
                return true;
            }
        }
        return false;
    }

    // ─── Web fetch domains ──────────────────────────────────────

    public void allowWebFetchDomain(String domain) {
        allowedWebFetchDomains.add(domain);
    }

    public boolean isWebFetchDomainAllowed(String domain) {
        return allowedWebFetchDomains.contains(domain);
    }

    // ─── MCP tools/servers ──────────────────────────────────────

    public void allowMcpTool(String toolName) {
        allowedMcpTools.add(toolName);
    }

    public boolean isMcpToolAllowed(String toolName) {
        return allowedMcpTools.contains(toolName);
    }

    public void allowMcpServer(String serverName) {
        allowedMcpServers.add(serverName);
    }

    public boolean isMcpServerAllowed(String serverName) {
        return allowedMcpServers.contains(serverName);
    }

    // ─── Bulk operations ────────────────────────────────────────

    public Set<String> getAllowedBashCommands() {
        return Collections.unmodifiableSet(allowedBashCommands);
    }

    public Set<String> getDisallowedBashCommands() {
        return Collections.unmodifiableSet(disallowedBashCommands);
    }

    public Set<String> getAllowedWebFetchDomains() {
        return Collections.unmodifiableSet(allowedWebFetchDomains);
    }

    public Set<String> getAllowedMcpTools() {
        return Collections.unmodifiableSet(allowedMcpTools);
    }

    public Set<String> getAllowedMcpServers() {
        return Collections.unmodifiableSet(allowedMcpServers);
    }

    public void clear() {
        allowBashExecute = false;
        allowedBashCommands.clear();
        disallowedBashCommands.clear();
        allowedBashGlobs.clear();
        allowedWebFetchDomains.clear();
        allowedMcpTools.clear();
        allowedMcpServers.clear();
    }
}
