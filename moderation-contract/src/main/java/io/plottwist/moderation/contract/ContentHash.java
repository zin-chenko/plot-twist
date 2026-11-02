package io.plottwist.moderation.contract;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

public final class ContentHash {
  private ContentHash() {}

  public static String of(String title, String body) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest((title + "\0" + body).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
