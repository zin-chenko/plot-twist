package io.plottwist.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import com.nimbusds.jose.jwk.RSAKey;

final class SigningKeys {
    private SigningKeys() {}

    // Single-instance development issuer. Production keys should be provisioned/rotated externally.
    static RSAKey loadOrCreate(Path path) throws Exception { return load(path, true); }

    static RSAKey load(Path path, boolean autoCreate) throws Exception {
        if (!Files.exists(path) && !autoCreate) throw new IllegalStateException("Signing key must be provisioned before startup");
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            var key = generator.generateKeyPair().getPrivate();
            var temporary = Files.createTempFile(path.getParent(), "signing-", ".pk8",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                Files.write(temporary, key.getEncoded());
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        var factory = KeyFactory.getInstance("RSA");
        var privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
            new PKCS8EncodedKeySpec(Files.readAllBytes(path)));
        var publicKey = (RSAPublicKey) factory.generatePublic(
            new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        var keyId = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded()));
        return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId).build();
    }
}
