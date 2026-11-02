package io.plottwist.domain.attachment.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class TicketSigner {
  public static final long TTL_SECONDS = 300;
  private final byte[] key;
  private final Clock clock;

  public TicketSigner(String key, Clock clock) {
    this.key = key.getBytes(StandardCharsets.UTF_8);
    this.clock = clock;
    if (this.key.length < 32)
      throw new IllegalArgumentException("Media signing key must have at least 32 bytes");
  }

  public String issue(UUID asset, UUID viewer) {
    String data =
        "1|"
            + asset
            + "|"
            + viewer
            + "|"
            + clock.instant().plusSeconds(TTL_SECONDS).getEpochSecond();
    var encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(data.getBytes(StandardCharsets.UTF_8));
    return encoded + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(encoded));
  }

  public UUID verify(UUID expected, String ticket) {
    try {
      if (ticket == null || ticket.length() > 300) throw new IllegalArgumentException();
      var parts = ticket.split("\\.", -1);
      if (parts.length != 2
          || !MessageDigest.isEqual(sign(parts[0]), Base64.getUrlDecoder().decode(parts[1])))
        throw new IllegalArgumentException();
      var fields =
          new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (fields.length != 4
          || !fields[0].equals("1")
          || !UUID.fromString(fields[1]).equals(expected)
          || Long.parseLong(fields[3]) <= clock.instant().getEpochSecond())
        throw new IllegalArgumentException();
      return UUID.fromString(fields[2]);
    } catch (IllegalArgumentException invalid) {
      throw new AttachmentException(
          AttachmentException.Reason.NOT_FOUND, "Media access link expired or invalid");
    }
  }

  private byte[] sign(String value) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
    } catch (java.security.GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
