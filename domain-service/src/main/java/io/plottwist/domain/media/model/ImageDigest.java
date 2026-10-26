package io.plottwist.domain.media.model;

import java.security.MessageDigest;
import java.util.HexFormat;

public final class ImageDigest {
  private ImageDigest() {}

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
