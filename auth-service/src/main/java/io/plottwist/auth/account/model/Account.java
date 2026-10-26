package io.plottwist.auth.account.model;

import java.util.UUID;

public record Account(UUID id, String username, boolean verified) {
  @Override
  public String toString() {
    return "Account[id=" + id + ", verified=" + verified + "]";
  }
}
