package io.plottwist.domain.profile.application.port;

import io.plottwist.domain.profile.model.FollowMember;
import io.plottwist.domain.profile.model.FollowPosition;
import io.plottwist.domain.profile.model.FollowState;
import java.util.List;
import java.util.UUID;

public interface FollowRepository {
  void lock(UUID viewer, UUID target);

  void setFollowing(UUID viewer, UUID target, boolean following);

  FollowState state(UUID viewer, UUID target);

  List<FollowMember> members(
      UUID viewer, UUID account, boolean followers, FollowPosition before, int limit);
}
