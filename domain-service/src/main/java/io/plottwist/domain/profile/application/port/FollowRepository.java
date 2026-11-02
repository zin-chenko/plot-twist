package io.plottwist.domain.profile.application.port;

import io.plottwist.domain.profile.model.FollowState;
import java.util.UUID;

public interface FollowRepository {
  void lock(UUID viewer, UUID target);

  void setFollowing(UUID viewer, UUID target, boolean following);

  FollowState state(UUID viewer, UUID target);
}
