package io.plottwist.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
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
    void rejectsAmbiguousOrNonHttpRedirects() {
        for (String url : new String[]{"/auth/callback", "javascript:alert(1)",
                "https://user:pass@example.com/auth/callback", "https://app.example.com/auth/callback?next=x",
                "https://app.example.com/auth/callback#fragment"})
            assertThrows(IllegalArgumentException.class, () -> AuthConfig.browserClient(url));
    }
}
