package io.plottwist.domain;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Date;
import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.*;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.junit.jupiter.api.Assertions.*;

class JwtValidationTest {
    private RSAKey key;
    private HttpServer server;
    private JwtDecoder decoder;

    @BeforeEach
    void setup() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            var body = new JWKSet(key.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        decoder = new SecurityConfig().jwtDecoder(new RestTemplateBuilder(), "https://issuer.test",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks");
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String token(String issuer, String audience, Instant expires, RSAKey signer) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject("test-user")
            .audience(audience).issueTime(Date.from(Instant.now().minusSeconds(300)))
            .expirationTime(Date.from(expires)).claim("scope", "api.read").build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
        jwt.sign(new RSASSASigner(signer));
        return jwt.serialize();
    }

    @Test
    void acceptsTrustedToken() throws Exception {
        var jwt = token("https://issuer.test", "plot-twist-domain", Instant.now().plusSeconds(300), key);
        assertEquals("test-user", decoder.decode(jwt).getSubject());
    }

    @Test
    void rejectsWrongIssuerAudienceExpiryAndSignature() throws Exception {
        var expiry = Instant.now().plusSeconds(300);
        var wrongIssuer = token("https://attacker.test", "plot-twist-domain", expiry, key);
        var wrongAudience = token("https://issuer.test", "other-service", expiry, key);
        var expired = token("https://issuer.test", "plot-twist-domain", Instant.now().minusSeconds(120), key);
        var forged = token("https://issuer.test", "plot-twist-domain", expiry,
            new RSAKeyGenerator(2048).keyID("test-key").generate());
        for (var jwt : new String[]{wrongIssuer, wrongAudience, expired, forged}) {
            assertThrows(JwtException.class, () -> decoder.decode(jwt));
        }
    }
}
