package io.plottwist.domain.story.application;

import io.plottwist.domain.media.application.MediaException;
import io.plottwist.domain.media.application.port.ObjectStorage;
import io.plottwist.domain.media.model.ImageDigest;
import io.plottwist.domain.story.application.port.StoryRepository;
import io.plottwist.domain.story.model.AuthorPhoto;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class StoryAuthorPhotos {
  private final StoryRepository stories;
  private final ObjectStorage storage;

  public StoryAuthorPhotos(StoryRepository stories, ObjectStorage storage) {
    this.stories = stories;
    this.storage = storage;
  }

  public AuthorPhoto metadata(UUID story) {
    return stories
        .authorPhoto(story)
        .orElseThrow(
            () -> new StoryException(StoryException.Reason.NOT_FOUND, "Author photo not found"));
  }

  public byte[] read(AuthorPhoto photo) {
    byte[] bytes = storage.get(photo.objectKey());
    if (!ImageDigest.sha256(bytes).equals(photo.sha256()))
      throw new MediaException(MediaException.Reason.UNAVAILABLE, "Author photo changed; retry");
    return bytes;
  }
}
