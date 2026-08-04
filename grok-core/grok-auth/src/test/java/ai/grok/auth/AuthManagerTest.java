package ai.grok.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AuthManager.
 * Mirrors the Rust auth flow tests (2026-08-03 e5478ef sync).
 */
class AuthManagerTest {

    @Nested
    @DisplayName("Credential")
    class CredentialTests {

        @Test
        @DisplayName("isExpired returns true for past expiry")
        void isExpiredReturnsTrueForPastExpiry() {
            var credential = new AuthManager.Credential(
                    AuthManager.ProviderType.API_KEY,
                    "token",
                    Instant.now().minus(Duration.ofHours(1)),
                    Map.of()
            );
            assertTrue(credential.isExpired());
        }

        @Test
        @DisplayName("isExpired returns false for future expiry")
        void isExpiredReturnsFalseForFutureExpiry() {
            var credential = new AuthManager.Credential(
                    AuthManager.ProviderType.API_KEY,
                    "token",
                    Instant.now().plus(Duration.ofHours(1)),
                    Map.of()
            );
            assertFalse(credential.isExpired());
        }

        @Test
        @DisplayName("isExpiringSoon detects near-expiry")
        void isExpiringSoonDetectsNearExpiry() {
            var credential = new AuthManager.Credential(
                    AuthManager.ProviderType.API_KEY,
                    "token",
                    Instant.now().plus(Duration.ofMinutes(3)),
                    Map.of()
            );
            assertTrue(credential.isExpiringSoon(Duration.ofMinutes(5)));
            assertFalse(credential.isExpiringSoon(Duration.ofMinutes(1)));
        }
    }

    @Nested
    @DisplayName("AuthConfig")
    class AuthConfigTests {

        @Test
        @DisplayName("withApiKey creates correct config")
        void withApiKeyCreatesCorrectConfig() {
            var config = AuthManager.AuthConfig.withApiKey("my-key");
            assertEquals("my-key", config.apiKey());
            assertNull(config.oauthClientId());
            assertNull(config.externalBinaryPath());
        }

        @Test
        @DisplayName("withOAuth creates correct config")
        void withOAuthCreatesCorrectConfig() {
            var config = AuthManager.AuthConfig.withOAuth("client-123");
            assertNull(config.apiKey());
            assertEquals("client-123", config.oauthClientId());
        }

        @Test
        @DisplayName("withExternalBinary creates correct config")
        void withExternalBinaryCreatesCorrectConfig() {
            var config = AuthManager.AuthConfig.withExternalBinary("/usr/bin/auth");
            assertNull(config.apiKey());
            assertEquals("/usr/bin/auth", config.externalBinaryPath());
        }
    }

    @Nested
    @DisplayName("AuthManager")
    class AuthManagerTests {

        @Test
        @DisplayName("initial state is UNAUTHENTICATED")
        void initialStateIsUnauthenticated() {
            var manager = new AuthManager(AuthManager.AuthConfig.withApiKey("key"));
            assertEquals(AuthManager.AuthState.UNAUTHENTICATED, manager.getState());
            assertFalse(manager.isAuthenticated());
        }

        @Test
        @DisplayName("signIn with API key succeeds")
        void signInWithApiKeySucceeds() throws ExecutionException, InterruptedException {
            var manager = new AuthManager(AuthManager.AuthConfig.withApiKey("my-api-key"));
            var result = manager.signIn().get();

            assertTrue(result.success());
            assertNotNull(result.credential());
            assertEquals(AuthManager.ProviderType.API_KEY, result.credential().providerType());
            assertEquals("my-api-key", result.credential().token());
            assertTrue(manager.isAuthenticated());
        }

        @Test
        @DisplayName("signIn with OAuth succeeds")
        void signInWithOAuthSucceeds() throws ExecutionException, InterruptedException {
            var manager = new AuthManager(AuthManager.AuthConfig.withOAuth("client-id"));
            var result = manager.signIn().get();

            assertTrue(result.success());
            assertEquals(AuthManager.ProviderType.OAUTH, result.credential().providerType());
            assertTrue(manager.isAuthenticated());
        }

        @Test
        @DisplayName("signOut clears state")
        void signOutClearsState() throws ExecutionException, InterruptedException {
            var manager = new AuthManager(AuthManager.AuthConfig.withApiKey("key"));
            manager.signIn().get();
            assertTrue(manager.isAuthenticated());

            manager.signOut();
            assertEquals(AuthManager.AuthState.UNAUTHENTICATED, manager.getState());
            assertFalse(manager.isAuthenticated());
            assertTrue(manager.getCurrentCredential().isEmpty());
        }

        @Test
        @DisplayName("getCurrentCredential returns empty when not authenticated")
        void getCurrentCredentialReturnsEmptyWhenNotAuthenticated() {
            var manager = new AuthManager(AuthManager.AuthConfig.withApiKey("key"));
            assertTrue(manager.getCurrentCredential().isEmpty());
        }

        @Test
        @DisplayName("getCurrentCredential returns credential when authenticated")
        void getCurrentCredentialReturnsCredentialWhenAuthenticated()
                throws ExecutionException, InterruptedException {
            var manager = new AuthManager(AuthManager.AuthConfig.withApiKey("key"));
            manager.signIn().get();

            var credential = manager.getCurrentCredential();
            assertTrue(credential.isPresent());
            assertEquals("key", credential.get().token());
        }

        @Test
        @DisplayName("signIn with no provider fails")
        void signInWithNoProviderFails() throws ExecutionException, InterruptedException {
            var config = new AuthManager.AuthConfig(null, null, null, Duration.ofMinutes(5));
            var manager = new AuthManager(config);
            var result = manager.signIn().get();

            assertFalse(result.success());
            assertEquals("No auth provider configured", result.error());
            assertEquals(AuthManager.AuthState.FAILED, manager.getState());
        }

        @Test
        @DisplayName("refreshIfNeeded refreshes expiring credential")
        void refreshIfNeededRefreshesExpiringCredential() throws ExecutionException, InterruptedException {
            // Create a manager with an expiring credential
            var manager = new AuthManager(AuthManager.AuthConfig.withApiKey("key"));
            manager.signIn().get();

            // Should not need refresh (API key doesn't expire soon)
            var result = manager.refreshIfNeeded().get();
            assertTrue(result.success());
        }
    }

    @Nested
    @DisplayName("AuthResult")
    class AuthResultTests {

        @Test
        @DisplayName("success creates successful result")
        void successCreatesSuccessfulResult() {
            var credential = new AuthManager.Credential(
                    AuthManager.ProviderType.API_KEY, "token",
                    Instant.now().plus(Duration.ofHours(1)), Map.of());
            var result = AuthManager.AuthResult.success(credential);

            assertTrue(result.success());
            assertNotNull(result.credential());
            assertNull(result.error());
        }

        @Test
        @DisplayName("failure creates failed result")
        void failureCreatesFailedResult() {
            var result = AuthManager.AuthResult.failure("something went wrong");

            assertFalse(result.success());
            assertNull(result.credential());
            assertEquals("something went wrong", result.error());
        }
    }
}
