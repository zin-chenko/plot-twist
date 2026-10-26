package io.plottwist.domain;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ProfileOwnershipTest {
    @Test
    void bindsAccountMarkerToSubject() {
        var id = UUID.randomUUID();
        var jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(id.toString()).claim("account_id", id.toString()).build();
        assertEquals(id, ProfileController.owner(jwt));
        var mismatch = Jwt.withTokenValue("test").header("alg", "RS256").subject(UUID.randomUUID().toString()).claim("account_id", id.toString()).build();
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> ProfileController.owner(mismatch)).getStatusCode().value());
    }

    @Test
    void refusesMachineIdentityWithoutVerifiedAccount() {
        var jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject("plot-twist-cli").build();
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> ProfileController.owner(jwt)).getStatusCode().value());
    }
}
