package io.plottwist.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import static org.junit.jupiter.api.Assertions.*;

class BrowserClientTest {
    @Test
    void requiresPkceWithoutSecretOrRefreshTokenAndRegistersExactRedirects() {
        var client = AuthConfig.browserClient("https://app.example.com/auth/callback");
        assertTrue(client.getClientSettings().isRequireProofKey());
        assertNull(client.getClientSecret());
        assertEquals(java.util.Set.of(ClientAuthenticationMethod.NONE), client.getClientAuthenticationMethods());
        assertEquals(java.util.Set.of(AuthorizationGrantType.AUTHORIZATION_CODE), client.getAuthorizationGrantTypes());
        assertEquals(java.util.Set.of("https://app.example.com/auth/callback"), client.getRedirectUris());
        assertEquals(java.util.Set.of("https://app.example.com/signed-out"), client.getPostLogoutRedirectUris());
    }

    @Test
    void acceptsBothExactHttpLoopbackOriginsWithTheConfiguredPortAndPath() {
        for (String host : new String[]{"localhost", "127.0.0.1"}) {
            var client = AuthConfig.browserClient("http://" + host + ":4300/auth/callback");
            assertEquals(java.util.Set.of("http://localhost:4300/auth/callback",
                "http://127.0.0.1:4300/auth/callback"), client.getRedirectUris());
            assertEquals(java.util.Set.of("http://localhost:4300/signed-out",
                "http://127.0.0.1:4300/signed-out"), client.getPostLogoutRedirectUris());
        }
    }

    @Test
    void keepsHttpsAndNonLoopbackOriginsExactWithoutAliases() {
        for (String callback : new String[]{"https://localhost:4200/auth/callback",
                "http://localhost.example.test:4200/auth/callback", "https://app.example.test/auth/callback"})
            assertEquals(java.util.Set.of(callback), AuthConfig.browserClient(callback).getRedirectUris());
    }

    @Test
    void browserRedirectValidationRejectsOtherPortsPathsAndHostsWithoutRedirectingErrors() {
        var client = AuthConfig.browserClient("http://localhost:4200/auth/callback");
        for (String redirect : client.getRedirectUris())
            assertDoesNotThrow(() -> AuthConfig.validateBrowserRedirect(context(client, redirect)));
        for (String redirect : new String[]{null, "http://127.0.0.1:4300/auth/callback",
                "http://localhost:4300/auth/callback", "http://127.0.0.1:4200/other-callback",
                "http://localhost.example.test:4200/auth/callback"}) {
            var error = assertThrows(OAuth2AuthorizationCodeRequestAuthenticationException.class,
                () -> AuthConfig.validateBrowserRedirect(context(client, redirect)));
            assertNull(error.getAuthorizationCodeRequestAuthentication());
        }
    }

    @Test
    void keepsNativeClientOnTheStandardValidationPath() {
        var client = RegisteredClient.withId("native").clientId("plot-twist-web")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://127.0.0.1:18080/callback").build();
        assertDoesNotThrow(() -> AuthConfig.validateBrowserRedirect(context(client, "http://127.0.0.1:18081/callback")));
    }

    private static OAuth2AuthorizationCodeRequestAuthenticationContext context(RegisteredClient client, String redirect) {
        var principal = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
            "test-user", "", java.util.List.of());
        var request = new OAuth2AuthorizationCodeRequestAuthenticationToken("http://localhost:8080/oauth2/authorize",
            client.getClientId(), principal, redirect, "test-state", java.util.Set.of("openid"), java.util.Map.of());
        return OAuth2AuthorizationCodeRequestAuthenticationContext.with(request).registeredClient(client).build();
    }

    @Test
    void rejectsAmbiguousOrNonHttpRedirects() {
        for (String url : new String[]{"/auth/callback", "javascript:alert(1)",
                "https://user:pass@example.com/auth/callback", "https://app.example.com/auth/callback?next=x",
                "https://app.example.com/auth/callback#fragment"})
            assertThrows(IllegalArgumentException.class, () -> AuthConfig.browserClient(url));
    }
}
