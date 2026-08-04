package ai.grok.permission;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP (Model Context Protocol) kill switch enforcement.
 * Mirrors the Rust security fix from 2026-08-03:
 * "vendor-compat MCP kill switch is now actually enforced when reported as on"
 *
 * <p>Provides a centralized mechanism to disable MCP tool execution globally
 * or per-server. When the kill switch is engaged, all MCP tool calls are
 * rejected regardless of other permission settings.
 */
public class McpKillSwitch {

    /**
     * Per-server kill switches.
     */
    private final Set<String> killedServers;
    /**
     * Global kill switch state.
     */
    private volatile boolean globalKillSwitchEngaged;
    /**
     * Reason for the last kill switch engagement (for diagnostics).
     */
    private volatile String lastKillReason;

    public McpKillSwitch() {
        this.globalKillSwitchEngaged = false;
        this.killedServers = ConcurrentHashMap.newKeySet();
        this.lastKillReason = null;
    }

    // ─── Global kill switch ─────────────────────────────────────

    /**
     * Engage the global MCP kill switch.
     * When engaged, ALL MCP tool calls are rejected.
     */
    public void engageGlobal(String reason) {
        this.globalKillSwitchEngaged = true;
        this.lastKillReason = reason;
    }

    /**
     * Disengage the global MCP kill switch.
     */
    public void disengageGlobal() {
        this.globalKillSwitchEngaged = false;
        this.lastKillReason = null;
    }

    /**
     * Check if the global kill switch is engaged.
     */
    public boolean isGlobalEngaged() {
        return globalKillSwitchEngaged;
    }

    // ─── Per-server kill switch ─────────────────────────────────

    /**
     * Engage the kill switch for a specific MCP server.
     * All tools from this server will be rejected.
     */
    public void engageServer(String serverName, String reason) {
        killedServers.add(serverName);
        this.lastKillReason = reason;
    }

    /**
     * Disengage the kill switch for a specific MCP server.
     */
    public void disengageServer(String serverName) {
        killedServers.remove(serverName);
    }

    /**
     * Check if a specific server's kill switch is engaged.
     */
    public boolean isServerKilled(String serverName) {
        return killedServers.contains(serverName);
    }

    /**
     * Get all currently killed servers.
     */
    public Set<String> getKilledServers() {
        return Collections.unmodifiableSet(killedServers);
    }

    // ─── Permission check ───────────────────────────────────────

    /**
     * Check if an MCP tool call is allowed.
     * Returns a KillSwitchResult indicating whether the call should proceed.
     *
     * @param serverName the MCP server name
     * @param toolName   the tool name within the server
     * @return the result of the kill switch check
     */
    public KillSwitchResult check(String serverName, String toolName) {
        // Global kill switch takes precedence
        if (globalKillSwitchEngaged) {
            return KillSwitchResult.blocked(
                    "MCP kill switch engaged globally: " + lastKillReason
            );
        }

        // Per-server kill switch
        if (killedServers.contains(serverName)) {
            return KillSwitchResult.blocked(
                    "MCP kill switch engaged for server '" + serverName + "': " + lastKillReason
            );
        }

        return KillSwitchResult.allowed();
    }

    /**
     * Get the reason for the last kill switch engagement.
     */
    public String getLastKillReason() {
        return lastKillReason;
    }

    /**
     * Reset all kill switches (for testing).
     */
    public void reset() {
        globalKillSwitchEngaged = false;
        killedServers.clear();
        lastKillReason = null;
    }

    // ─── Result type ────────────────────────────────────────────

    /**
     * Result of a kill switch check.
     */
    public record KillSwitchResult(
            boolean permitted,
            String reason
    ) {
        public static KillSwitchResult allowed() {
            return new KillSwitchResult(true, null);
        }

        public static KillSwitchResult blocked(String reason) {
            return new KillSwitchResult(false, reason);
        }

        public boolean isBlocked() {
            return !permitted;
        }
    }
}
