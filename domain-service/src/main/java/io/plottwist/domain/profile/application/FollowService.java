package io.plottwist.domain.profile.application;

import io.plottwist.domain.profile.application.port.FollowRepository;
import io.plottwist.domain.profile.model.FollowPage;
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

  public FollowPage members(
      UUID viewer, UUID account, boolean followers, String cursor, int limit, String query) {
    if (limit < 1 || limit > 20)
      throw new ProfileException(
          ProfileException.Reason.INVALID_INPUT, "Page size must be between 1 and 20");
    String search = query == null ? "" : query.strip();
    if (search.length() > 80 || search.indexOf(0) >= 0)
      throw new ProfileException(
          ProfileException.Reason.INVALID_INPUT,
          "Search must be at most 80 characters without NUL");
    var before = FollowCursor.decode(cursor, account, followers, search);
    if (!viewer.equals(account)) profiles.publicProfile(account);
    var rows = follows.members(viewer, account, followers, before, limit + 1, search);
    var page = rows.subList(0, Math.min(limit, rows.size()));
    return new FollowPage(
        page.stream().map(row -> row.user()).toList(),
        rows.size() > limit
            ? FollowCursor.encode(account, followers, page.getLast().position(), search)
            : null);
  }
}
