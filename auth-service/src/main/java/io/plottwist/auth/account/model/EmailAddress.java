package io.plottwist.auth.account.model;

import java.util.Locale;

public final class EmailAddress {
  private EmailAddress() {}

  public static String normalize(String value) {
    return value.strip().toLowerCase(Locale.ROOT);
  }
}
