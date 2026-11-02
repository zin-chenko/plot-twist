package io.plottwist.domain.profile.application;

import io.plottwist.domain.profile.application.port.FollowRepository;
import io.plottwist.domain.profile.model.FollowState;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FollowService {
  private final FollowRepository follows;
  private final ProfileService profiles;

  public FollowService(FollowRepository follows, ProfileService profiles) {
    this.follows = follows;
    this.profiles = profiles;
  }

  @Transactional
  public FollowState setFollowing(UUID viewer, UUID target, boolean following) {
    if (viewer.equals(target))
      throw new ProfileException(
          ProfileException.Reason.INVALID_INPUT, "You cannot follow yourself");
    follows.lock(viewer, target);
    if (following) profiles.publicProfile(target);
    follows.setFollowing(viewer, target, following);
    return follows.state(viewer, target);
  }
}
