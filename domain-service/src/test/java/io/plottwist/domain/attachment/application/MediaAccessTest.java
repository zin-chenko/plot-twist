package io.plottwist.domain.attachment.application;

import static org.junit.jupiter.api.Assertions.*;

import io.plottwist.domain.attachment.model.ByteRange;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MediaAccessTest {
  @Test
  void ticketsAreBoundToAssetAndExpire() {
    var now = Instant.parse("2026-11-02T00:00:00Z");
    var key = "a".repeat(48);
    var signer = new TicketSigner(key, Clock.fixed(now, ZoneOffset.UTC));
    var asset = UUID.randomUUID();
    var viewer = UUID.randomUUID();
    var ticket = signer.issue(asset, viewer);
    assertEquals(viewer, signer.verify(asset, ticket));
    assertThrows(AttachmentException.class, () -> signer.verify(UUID.randomUUID(), ticket));
    var parts = ticket.split("\\.");
    var signature = Base64.getUrlDecoder().decode(parts[1]);
    signature[0] ^= 1;
    var forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    assertThrows(AttachmentException.class, () -> signer.verify(asset, forged));
    assertThrows(
        AttachmentException.class,
        () ->
            new TicketSigner(key, Clock.fixed(now.plusSeconds(301), ZoneOffset.UTC))
                .verify(asset, ticket));
  }

  @Test
  void supportsNativeVideoRangesAndRejectsUnsatisfiableOrMultipleRanges() {
    assertEquals(new ByteRange(0, 99, false), ByteRange.parse(null, 100));
    assertEquals(new ByteRange(0, 1, true), ByteRange.parse("bytes=0-1", 100));
    assertEquals(new ByteRange(10, 99, true), ByteRange.parse("bytes=10-", 100));
    assertEquals(new ByteRange(90, 99, true), ByteRange.parse("bytes=-10", 100));
    for (String value :
        List.of(
            "bytes=101-",
            "bytes=5-3",
            "bytes=0-1,5-7",
            "bytes=-0",
            "bytes=9999999999999999999999-"))
      assertThrows(IllegalArgumentException.class, () -> ByteRange.parse(value, 100));
  }
}
