package ai.grok.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.BeforeEach;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for grok-permission module.
 */
class PermissionTest {

    // ─── ExecRiskAssessor ──────────────────────────────────────────

    @Nested
    class ExecRiskAssessorTest {
        private ExecRiskAssessor assessor;

        @BeforeEach
        void setUp() {
            assessor = new ExecRiskAssessor();
        }

        @Test
        void emptyCommandShouldBeSafe() {
            var risk = assessor.assess("", null);
            assertEquals(ExecRiskAssessor.RiskLevel.SAFE, risk.level());
        }

        @Test
        void nullCommandShouldBeSafe() {
            var risk = assessor.assess(null, null);
            assertEquals(ExecRiskAssessor.RiskLevel.SAFE, risk.level());
        }

        @Test
        void simpleEchoShouldBeLowRisk() {
            var risk = assessor.assess("echo hello", null);
            assertEquals(ExecRiskAssessor.RiskLevel.LOW, risk.level());
        }

        @Test
        void rmShouldBeHighRisk() {
            var risk = assessor.assess("rm file.txt", null);
            assertEquals(ExecRiskAssessor.RiskLevel.HIGH, risk.level());
        }

        @Test
        void sudoShouldBeHighRisk() {
            var risk = assessor.assess("sudo apt install foo", null);
            assertEquals(ExecRiskAssessor.RiskLevel.HIGH, risk.level());
        }

        @Test
        void curlShouldBeHighRisk() {
            var risk = assessor.assess("curl https://example.com", null);
            assertEquals(ExecRiskAssessor.RiskLevel.HIGH, risk.level());
        }

        @Test
        void gitShouldBeMediumRisk() {
            var risk = assessor.assess("git push origin main", null);
            assertEquals(ExecRiskAssessor.RiskLevel.MEDIUM, risk.level());
        }

        @Test
        void gitStatusShouldBeSafeWithContext() {
            var ctx = ExecRiskAssessor.ShellContext.of("/repo", "status");
            var risk = assessor.assess("git status", ctx);
            assertEquals(ExecRiskAssessor.RiskLevel.SAFE, risk.level());
        }

        @Test
        void gitLogShouldBeSafeWithContext() {
            var ctx = ExecRiskAssessor.ShellContext.of("/repo", "log");
            var risk = assessor.assess("git log --oneline", ctx);
            assertEquals(ExecRiskAssessor.RiskLevel.SAFE, risk.level());
        }

        @Test
        void gitBlameShouldBeSafeWithContext() {
            var ctx = ExecRiskAssessor.ShellContext.of("/repo", "blame");
            var risk = assessor.assess("git blame file.txt", ctx);
            assertEquals(ExecRiskAssessor.RiskLevel.SAFE, risk.level());
        }

        @Test
        void gitGrepShouldBeSafeWithContext() {
            var ctx = ExecRiskAssessor.ShellContext.of("/repo", "grep");
            var risk = assessor.assess("git grep pattern", ctx);
            assertEquals(ExecRiskAssessor.RiskLevel.SAFE, risk.level());
        }

        @Test
        void gitDescribeShouldBeSafeWithContext() {
            var ctx = ExecRiskAssessor.ShellContext.of("/repo", "describe");
            var risk = assessor.assess("git describe --tags", ctx);
            assertEquals(ExecRiskAssessor.RiskLevel.SAFE, risk.level());
        }

        @Test
        void gitMergeBaseShouldBeSafeWithContext() {
            var ctx = ExecRiskAssessor.ShellContext.of("/repo", "merge-base");
            var risk = assessor.assess("git merge-base main feature", ctx);
            assertEquals(ExecRiskAssessor.RiskLevel.SAFE, risk.level());
        }

        @Test
        void gitShowWithUnsafeEditOptionShouldNotBeSafe() {
            var ctx = ExecRiskAssessor.ShellContext.of("/repo", "show", "git show --edit");
            assertFalse(ExecRiskAssessor.gitWordsAreReadOnlyQuery(ctx));
        }

        @Test
        void gitLogWithUnsafeOpenFilesOptionShouldNotBeSafe() {
            var ctx = ExecRiskAssessor.ShellContext.of("/repo", "log", "git log -Oless");
            assertFalse(ExecRiskAssessor.gitWordsAreReadOnlyQuery(ctx));
        }

        @Test
        void gitLogWithUnsafeInteractiveOptionShouldNotBeSafe() {
            var ctx = ExecRiskAssessor.ShellContext.of("/repo", "log", "git log --interactive");
            assertFalse(ExecRiskAssessor.gitWordsAreReadOnlyQuery(ctx));
        }

        @Test
        void gitWordsAreReadOnlyQueryShouldReturnFalseForNullContext() {
            assertFalse(ExecRiskAssessor.gitWordsAreReadOnlyQuery(null));
        }

        @Test
        void gitWordsAreReadOnlyQueryShouldReturnFalseForNullSubCommand() {
            var ctx = ExecRiskAssessor.ShellContext.of("/repo");
            assertFalse(ExecRiskAssessor.gitWordsAreReadOnlyQuery(ctx));
        }

        @Test
        void gitWordsAreReadOnlyQueryShouldReturnFalseForWriteCommand() {
            var ctx = ExecRiskAssessor.ShellContext.of("/repo", "push");
            assertFalse(ExecRiskAssessor.gitWordsAreReadOnlyQuery(ctx));
        }

        @Test
        void gitWordsHaveUnsafeQueryOptionShouldDetectEdit() {
            assertTrue(ExecRiskAssessor.gitWordsHaveUnsafeQueryOption("git show --edit"));
        }

        @Test
        void gitWordsHaveUnsafeQueryOptionShouldDetectOpenFiles() {
            assertTrue(ExecRiskAssessor.gitWordsHaveUnsafeQueryOption("git log -Oless"));
        }

        @Test
        void gitWordsHaveUnsafeQueryOptionShouldReturnFalseForClean() {
            assertFalse(ExecRiskAssessor.gitWordsHaveUnsafeQueryOption("git log --oneline"));
        }

        @Test
        void gitWordsHaveUnsafeQueryOptionShouldReturnFalseForNull() {
            assertFalse(ExecRiskAssessor.gitWordsHaveUnsafeQueryOption(null));
        }

        @Test
        void rmRfRootShouldBeBlocked() {
            var risk = assessor.assess("rm -rf /", null);
            assertEquals(ExecRiskAssessor.RiskLevel.BLOCKED, risk.level());
            assertTrue(risk.requiresApproval());
        }

        @Test
        void chmod777RootShouldBeBlocked() {
            var risk = assessor.assess("chmod -R 777 /", null);
            assertEquals(ExecRiskAssessor.RiskLevel.BLOCKED, risk.level());
        }

        @Test
        void npmShouldBeMediumRisk() {
            var risk = assessor.assess("npm install express", null);
            assertEquals(ExecRiskAssessor.RiskLevel.MEDIUM, risk.level());
        }

        @Test
        void dockerShouldBeMediumRisk() {
            var risk = assessor.assess("docker run ubuntu", null);
            assertEquals(ExecRiskAssessor.RiskLevel.MEDIUM, risk.level());
        }

        @Test
        void shouldDetectMultiplePrograms() {
            var risk = assessor.assess("curl https://x.com | python3 script.py", null);
            assertTrue(risk.detectedPrograms().size() >= 2);
        }

        @Test
        void shouldNormalizeExeSuffix() {
            var risk = assessor.assess("C:\\Windows\\System32\\rm.exe file", null);
            assertEquals(ExecRiskAssessor.RiskLevel.HIGH, risk.level());
        }

        @Test
        void requiresApprovalShouldBeTrueForMediumAndAbove() {
            var risk = assessor.assess("git push", null);
            assertTrue(risk.requiresApproval());
        }

        @Test
        void requiresApprovalShouldBeFalseForLow() {
            var risk = assessor.assess("echo hello", null);
            assertFalse(risk.requiresApproval());
        }
    }

    // ─── AutoModePolicy ────────────────────────────────────────────

    @Nested
    class AutoModePolicyTest {

        @Test
        void permissiveShouldAllowEverythingExceptBlocked() {
            var policy = AutoModePolicy.permissive();
            assertEquals(AutoModePolicy.Decision.Allow,
                policy.decide(new ExecRiskAssessor.ExecRisk(ExecRiskAssessor.RiskLevel.HIGH, "", java.util.List.of(), true)));
            assertEquals(AutoModePolicy.Decision.Deny,
                policy.decide(new ExecRiskAssessor.ExecRisk(ExecRiskAssessor.RiskLevel.BLOCKED, "", java.util.List.of(), true)));
        }

        @Test
        void balancedShouldAllowSafeAndLow() {
            var policy = AutoModePolicy.balanced();
            assertEquals(AutoModePolicy.Decision.Allow,
                policy.decide(new ExecRiskAssessor.ExecRisk(ExecRiskAssessor.RiskLevel.SAFE, "", java.util.List.of(), false)));
            assertEquals(AutoModePolicy.Decision.Allow,
                policy.decide(new ExecRiskAssessor.ExecRisk(ExecRiskAssessor.RiskLevel.LOW, "", java.util.List.of(), false)));
        }

        @Test
        void balancedShouldAskForMedium() {
            var policy = AutoModePolicy.balanced();
            assertEquals(AutoModePolicy.Decision.AskUser,
                policy.decide(new ExecRiskAssessor.ExecRisk(ExecRiskAssessor.RiskLevel.MEDIUM, "", java.util.List.of(), true)));
        }

        @Test
        void balancedShouldDenyHighAndBlocked() {
            var policy = AutoModePolicy.balanced();
            assertEquals(AutoModePolicy.Decision.Deny,
                policy.decide(new ExecRiskAssessor.ExecRisk(ExecRiskAssessor.RiskLevel.HIGH, "", java.util.List.of(), true)));
            assertEquals(AutoModePolicy.Decision.Deny,
                policy.decide(new ExecRiskAssessor.ExecRisk(ExecRiskAssessor.RiskLevel.BLOCKED, "", java.util.List.of(), true)));
        }

        @Test
        void strictShouldOnlyAllowSafe() {
            var policy = AutoModePolicy.strict();
            assertEquals(AutoModePolicy.Decision.Allow,
                policy.decide(new ExecRiskAssessor.ExecRisk(ExecRiskAssessor.RiskLevel.SAFE, "", java.util.List.of(), false)));
            assertEquals(AutoModePolicy.Decision.AskUser,
                policy.decide(new ExecRiskAssessor.ExecRisk(ExecRiskAssessor.RiskLevel.LOW, "", java.util.List.of(), false)));
        }

        @Test
        void lockdownShouldDenyAlmostEverything() {
            var policy = new AutoModePolicy(AutoModePolicy.Preset.LOCKDOWN);
            assertEquals(AutoModePolicy.Decision.Allow,
                policy.decide(new ExecRiskAssessor.ExecRisk(ExecRiskAssessor.RiskLevel.SAFE, "", java.util.List.of(), false)));
            assertEquals(AutoModePolicy.Decision.Deny,
                policy.decide(new ExecRiskAssessor.ExecRisk(ExecRiskAssessor.RiskLevel.LOW, "", java.util.List.of(), false)));
        }
    }

    // ─── PermissionState ─────────────────────────────────────────

    @Nested
    class PermissionStateTest {
        private PermissionState state;

        @BeforeEach
        void setUp() {
            state = new PermissionState();
        }

        @Test
        void initialStateShouldBeEmpty() {
            assertFalse(state.isAllowBashExecute());
            assertTrue(state.getAllowedBashCommands().isEmpty());
            assertTrue(state.getDisallowedBashCommands().isEmpty());
            assertTrue(state.getAllowedBashGlobs().isEmpty());
            assertTrue(state.getAllowedWebFetchDomains().isEmpty());
            assertTrue(state.getAllowedMcpTools().isEmpty());
            assertTrue(state.getAllowedMcpServers().isEmpty());
        }

        @Test
        void bashCommandAllowAndCheck() {
            state.allowBashCommand("git status");
            assertTrue(state.isBashCommandAllowed("git status"));
            assertFalse(state.isBashCommandAllowed("git push"));
        }

        @Test
        void bashCommandDisallowAndCheck() {
            state.disallowBashCommand("rm -rf /");
            assertTrue(state.isBashCommandDisallowed("rm -rf /"));
            assertFalse(state.isBashCommandDisallowed("echo hello"));
        }

        @Test
        void bashGlobMatchingExact() {
            state.allowBashGlob("git status");
            assertTrue(state.isBashCommandMatchedByGlob("git status"));
            assertFalse(state.isBashCommandMatchedByGlob("git push"));
        }

        @Test
        void bashGlobMatchingWildcard() {
            state.allowBashGlob("gh api repos/owner/*");
            assertTrue(state.isBashCommandMatchedByGlob("gh api repos/owner/repo1"));
            assertTrue(state.isBashCommandMatchedByGlob("gh api repos/owner/repo2"));
            assertFalse(state.isBashCommandMatchedByGlob("gh api repos/other/repo1"));
        }

        @Test
        void bashGlobMatchingMultiplePatterns() {
            state.allowBashGlob("cargo *");
            state.allowBashGlob("npm *");
            assertTrue(state.isBashCommandMatchedByGlob("cargo build"));
            assertTrue(state.isBashCommandMatchedByGlob("npm install"));
            assertFalse(state.isBashCommandMatchedByGlob("pip install"));
        }

        @Test
        void bashGlobQuestionMark() {
            state.allowBashGlob("git ?");
            assertTrue(state.isBashCommandMatchedByGlob("git a"));
            assertFalse(state.isBashCommandMatchedByGlob("git ab"));
        }

        @Test
        void webFetchDomainAllowAndCheck() {
            state.allowWebFetchDomain("api.example.com");
            assertTrue(state.isWebFetchDomainAllowed("api.example.com"));
            assertFalse(state.isWebFetchDomainAllowed("other.example.com"));
        }

        @Test
        void mcpToolAllowAndCheck() {
            state.allowMcpTool("server__tool1");
            assertTrue(state.isMcpToolAllowed("server__tool1"));
            assertFalse(state.isMcpToolAllowed("server__tool2"));
        }

        @Test
        void mcpServerAllowAndCheck() {
            state.allowMcpServer("my-server");
            assertTrue(state.isMcpServerAllowed("my-server"));
            assertFalse(state.isMcpServerAllowed("other-server"));
        }

        @Test
        void clearShouldResetAllState() {
            state.setAllowBashExecute(true);
            state.allowBashCommand("cmd");
            state.disallowBashCommand("bad");
            state.allowBashGlob("pattern*");
            state.allowWebFetchDomain("example.com");
            state.allowMcpTool("tool");
            state.allowMcpServer("server");

            state.clear();

            assertFalse(state.isAllowBashExecute());
            assertTrue(state.getAllowedBashCommands().isEmpty());
            assertTrue(state.getDisallowedBashCommands().isEmpty());
            assertTrue(state.getAllowedBashGlobs().isEmpty());
            assertTrue(state.getAllowedWebFetchDomains().isEmpty());
            assertTrue(state.getAllowedMcpTools().isEmpty());
            assertTrue(state.getAllowedMcpServers().isEmpty());
        }
    }

    // ─── RequestPathContext ──────────────────────────────────────

    @Nested
    class RequestPathContextTest {

        @Test
        void ofWithSingleCwdShouldHaveEmptyDisplayCwd() {
            var ctx = RequestPathContext.of(java.nio.file.Path.of("/work"));
            assertEquals(java.nio.file.Path.of("/work"), ctx.realCwd());
            assertTrue(ctx.displayCwd().isEmpty());
        }

        @Test
        void ofWithBothCwdsShouldPreserveBoth() {
            var ctx = RequestPathContext.of(
                java.nio.file.Path.of("/real"),
                java.nio.file.Path.of("/display")
            );
            assertEquals(java.nio.file.Path.of("/real"), ctx.realCwd());
            assertEquals(java.nio.file.Path.of("/display"), ctx.displayCwd().get());
        }

        @Test
        void resolveShouldAnchorRelativeToRealCwd() {
            var ctx = RequestPathContext.of(java.nio.file.Path.of("/work"));
            var resolved = ctx.resolve("src/main.java");
            assertEquals(java.nio.file.Path.of("/work/src/main.java"), resolved);
        }

        @Test
        void resolveShouldKeepAbsolutePathsUnchanged() {
            var ctx = RequestPathContext.of(java.nio.file.Path.of("/work"));
            var resolved = ctx.resolve("/other/file.txt");
            assertEquals(java.nio.file.Path.of("/other/file.txt"), resolved);
        }

        @Test
        void ruleCwdShouldReturnRealCwd() {
            var ctx = RequestPathContext.of(java.nio.file.Path.of("/work"));
            assertEquals(java.nio.file.Path.of("/work"), ctx.ruleCwd());
        }

        @Test
        void effectiveDisplayCwdShouldFallbackToReal() {
            var ctx = RequestPathContext.of(java.nio.file.Path.of("/work"));
            assertEquals(java.nio.file.Path.of("/work"), ctx.effectiveDisplayCwd());
        }

        @Test
        void effectiveDisplayCwdShouldReturnDisplayWhenSet() {
            var ctx = RequestPathContext.of(
                java.nio.file.Path.of("/real"),
                java.nio.file.Path.of("/display")
            );
            assertEquals(java.nio.file.Path.of("/display"), ctx.effectiveDisplayCwd());
        }
    }

    // ─── McpKillSwitch ───────────────────────────────────────────

    @Nested
    class McpKillSwitchTest {
        private McpKillSwitch killSwitch;

        @BeforeEach
        void setUp() {
            killSwitch = new McpKillSwitch();
        }

        @Test
        void initialStateShouldAllowAll() {
            var result = killSwitch.check("server1", "tool1");
            assertTrue(result.permitted());
            assertFalse(result.isBlocked());
        }

        @Test
        void globalKillSwitchShouldBlockAll() {
            killSwitch.engageGlobal("security alert");
            assertTrue(killSwitch.isGlobalEngaged());

            var result = killSwitch.check("any-server", "any-tool");
            assertFalse(result.permitted());
            assertTrue(result.isBlocked());
            assertTrue(result.reason().contains("globally"));
        }

        @Test
        void disengageGlobalShouldRestoreAccess() {
            killSwitch.engageGlobal("alert");
            killSwitch.disengageGlobal();
            assertFalse(killSwitch.isGlobalEngaged());

            var result = killSwitch.check("server", "tool");
            assertTrue(result.permitted());
        }

        @Test
        void perServerKillSwitchShouldBlockOnlyThatServer() {
            killSwitch.engageServer("bad-server", "misbehaving");

            var blocked = killSwitch.check("bad-server", "tool");
            assertFalse(blocked.permitted());
            assertTrue(blocked.reason().contains("bad-server"));

            var allowed = killSwitch.check("good-server", "tool");
            assertTrue(allowed.permitted());
        }

        @Test
        void disengageServerShouldRestoreAccess() {
            killSwitch.engageServer("server1", "reason");
            killSwitch.disengageServer("server1");

            var result = killSwitch.check("server1", "tool");
            assertTrue(result.permitted());
        }

        @Test
        void globalShouldTakePrecedenceOverServer() {
            killSwitch.engageServer("server1", "server reason");
            killSwitch.engageGlobal("global reason");

            var result = killSwitch.check("server2", "tool");
            assertFalse(result.permitted());
            assertTrue(result.reason().contains("globally"));
        }

        @Test
        void getKilledServersShouldReturnAllKilled() {
            killSwitch.engageServer("s1", "r1");
            killSwitch.engageServer("s2", "r2");
            assertEquals(Set.of("s1", "s2"), killSwitch.getKilledServers());
        }

        @Test
        void resetShouldClearEverything() {
            killSwitch.engageGlobal("global");
            killSwitch.engageServer("s1", "r1");

            killSwitch.reset();

            assertFalse(killSwitch.isGlobalEngaged());
            assertTrue(killSwitch.getKilledServers().isEmpty());
            assertNull(killSwitch.getLastKillReason());
        }

        @Test
        void lastKillReasonShouldBeTracked() {
            killSwitch.engageServer("s1", "first reason");
            assertEquals("first reason", killSwitch.getLastKillReason());

            killSwitch.engageServer("s2", "second reason");
            assertEquals("second reason", killSwitch.getLastKillReason());
        }
    }

    // ─── GatePreflight ─────────────────────────────────────────────

    @Nested
    class GatePreflightTest {

        @Test
        void safeCommandShouldBeApproved() {
            var assessor = new ExecRiskAssessor();
            var policy = AutoModePolicy.balanced();
            var gate = new GatePreflight(assessor, policy);

            var result = gate.check("echo hello", null);
            assertEquals(GatePreflight.PreflightStatus.APPROVED, result.status());
            assertTrue(result.canProceed());
        }

        @Test
        void dangerousCommandShouldBeDenied() {
            var assessor = new ExecRiskAssessor();
            var policy = AutoModePolicy.balanced();
            var gate = new GatePreflight(assessor, policy);

            var result = gate.check("rm -rf /", null);
            assertEquals(GatePreflight.PreflightStatus.DENIED, result.status());
            assertFalse(result.canProceed());
        }

        @Test
        void mediumRiskShouldNeedApproval() {
            var assessor = new ExecRiskAssessor();
            var policy = AutoModePolicy.balanced();
            var gate = new GatePreflight(assessor, policy);

            var result = gate.check("git push origin main", null);
            assertEquals(GatePreflight.PreflightStatus.NEEDS_APPROVAL, result.status());
            assertFalse(result.canProceed());
        }
    }
}
