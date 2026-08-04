package ai.grok.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Authentication flow and credential management.
 * Mirrors the Rust `xai-grok-shell/src/auth/flow.rs` (2026-08-03 e5478ef sync).
 *
 * <p>Features:
 * <ul>
 *   <li>Multiple auth providers (API key, OAuth, external binary)</li>
 *   <li>Treat sign-in as a fresh login, not a refresh (2026-08-03 change)</li>
 *   <li>Credential storage and retrieval</li>
 *   <li>Token refresh handling</li>
 * </ul>
 */
public final class AuthManager {

    private static final Logger log = LoggerFactory.getLogger(AuthManager.class);

    private final AuthConfig config;
    private final CredentialStore credentialStore;
    private volatile AuthState currentState = AuthState.UNAUTHENTICATED;
    private volatile Credential currentCredential;

    /**
     * Authentication state.
     */
    public enum AuthState {
        UNAUTHENTICATED,
        AUTHENTICATING,
        AUTHENTICATED,
        EXPIRED,
        FAILED
    }

    /**
     * Auth provider types.
     */
    public enum ProviderType {
        API_KEY,
        OAUTH,
        EXTERNAL_BINARY
    }

    /**
     * Authentication configuration.
     */
    public record AuthConfig(
            String apiKey,
            String oauthClientId,
            String externalBinaryPath,
            Duration tokenRefreshBuffer
    ) {
        public static AuthConfig withApiKey(String apiKey) {
            return new AuthConfig(apiKey, null, null, Duration.ofMinutes(5));
        }

        public static AuthConfig withOAuth(String clientId) {
            return new AuthConfig(null, clientId, null, Duration.ofMinutes(5));
        }

        public static AuthConfig withExternalBinary(String binaryPath) {
            return new AuthConfig(null, null, binaryPath, Duration.ofMinutes(5));
        }
    }

    /**
     * Stored credential.
     */
    public record Credential(
            ProviderType providerType,
            String token,
            Instant expiresAt,
            Map<String, String> metadata
    ) {
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }

        public boolean isExpiringSoon(Duration buffer) {
            return Instant.now().plus(buffer).isAfter(expiresAt);
        }
    }

    /**
     * Authentication result.
     */
    public record AuthResult(boolean success, Credential credential, String error) {
        public static AuthResult success(Credential credential) {
            return new AuthResult(true, credential, null);
        }

        public static AuthResult failure(String error) {
            return new AuthResult(false, null, error);
        }
    }

    public AuthManager(AuthConfig config) {
        this.config = config;
        this.credentialStore = new CredentialStore();
    }

    /**
     * Sign in with the configured provider.
     *
     * <p>Treats sign-in as a fresh login, not a refresh (2026-08-03 change).
     * This ensures external-binary auth always gets fresh credentials.
     *
     * @return auth result
     */
    public CompletableFuture<AuthResult> signIn() {
        currentState = AuthState.AUTHENTICATING;

        return CompletableFuture.supplyAsync(() -> {
            try {
                AuthResult result;

                if (config.apiKey() != null) {
                    result = signInWithApiKey(config.apiKey());
                } else if (config.oauthClientId() != null) {
                    result = signInWithOAuth(config.oauthClientId());
                } else if (config.externalBinaryPath() != null) {
                    result = signInWithExternalBinary(config.externalBinaryPath());
                } else {
                    result = AuthResult.failure("No auth provider configured");
                }

                if (result.success()) {
                    currentCredential = result.credential();
                    credentialStore.store(currentCredential);
                    currentState = AuthState.AUTHENTICATED;
                    log.info("Authentication successful with {}", result.credential().providerType());
                } else {
                    currentState = AuthState.FAILED;
                    log.warn("Authentication failed: {}", result.error());
                }

                return result;

            } catch (Exception e) {
                currentState = AuthState.FAILED;
                log.error("Authentication error", e);
                return AuthResult.failure(e.getMessage());
            }
        });
    }

    /**
     * Sign out and clear credentials.
     */
    public void signOut() {
        currentCredential = null;
        credentialStore.clear();
        currentState = AuthState.UNAUTHENTICATED;
        log.info("Signed out");
    }

    /**
     * Get the current authentication state.
     */
    public AuthState getState() {
        return currentState;
    }

    /**
     * Get the current credential if authenticated.
     */
    public Optional<Credential> getCurrentCredential() {
        if (currentCredential != null && !currentCredential.isExpired()) {
            return Optional.of(currentCredential);
        }
        return Optional.empty();
    }

    /**
     * Check if currently authenticated with a valid credential.
     */
    public boolean isAuthenticated() {
        return currentState == AuthState.AUTHENTICATED && getCurrentCredential().isPresent();
    }

    /**
     * Refresh the current credential if it's expiring soon.
     *
     * <p>Note: For external-binary auth, this performs a fresh login rather than
     * a refresh (2026-08-03 change).
     */
    public CompletableFuture<AuthResult> refreshIfNeeded() {
        if (currentCredential == null) {
            return CompletableFuture.completedFuture(AuthResult.failure("Not authenticated"));
        }

        if (!currentCredential.isExpiringSoon(config.tokenRefreshBuffer())) {
            return CompletableFuture.completedFuture(AuthResult.success(currentCredential));
        }

        // Treat as fresh login for external binary auth
        log.info("Credential expiring soon, performing fresh login");
        return signIn();
    }

    private AuthResult signInWithApiKey(String apiKey) {
        // API key auth doesn't expire
        Credential credential = new Credential(
                ProviderType.API_KEY,
                apiKey,
                Instant.now().plus(Duration.ofDays(365)),
                Map.of()
        );
        return AuthResult.success(credential);
    }

    private AuthResult signInWithOAuth(String clientId) {
        // Simplified OAuth flow - in production this would involve browser redirect
        try {
            // For now, just create a placeholder credential
            Credential credential = new Credential(
                    ProviderType.OAUTH,
                    "oauth-token-" + System.currentTimeMillis(),
                    Instant.now().plus(Duration.ofHours(1)),
                    Map.of("client_id", clientId)
            );
            return AuthResult.success(credential);
        } catch (Exception e) {
            return AuthResult.failure("OAuth sign-in failed: " + e.getMessage());
        }
    }

    private AuthResult signInWithExternalBinary(String binaryPath) {
        // External binary auth - treat as fresh login (2026-08-03 change)
        try {
            ProcessBuilder pb = new ProcessBuilder(binaryPath, "get-token");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String token = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                return AuthResult.failure("External binary exited with code " + exitCode);
            }

            Credential credential = new Credential(
                    ProviderType.EXTERNAL_BINARY,
                    token,
                    Instant.now().plus(Duration.ofHours(1)),
                    Map.of("binary", binaryPath)
            );
            return AuthResult.success(credential);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return AuthResult.failure("External binary auth failed: " + e.getMessage());
        }
    }

    /**
     * Simple in-memory credential store.
     */
    private static class CredentialStore {
        private volatile Credential stored;

        void store(Credential credential) {
            this.stored = credential;
        }

        Optional<Credential> load() {
            return Optional.ofNullable(stored);
        }

        void clear() {
            this.stored = null;
        }
    }
}
