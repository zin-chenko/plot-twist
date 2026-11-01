package io.plottwist.domain.story.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.plottwist.domain.media.application.MediaException;
import io.plottwist.domain.media.application.port.ObjectStorage;
import io.plottwist.domain.media.model.ImageDigest;
import io.plottwist.domain.story.application.port.StoryRepository;
import io.plottwist.domain.story.model.AuthorPhoto;
import java.util.*;
import org.junit.jupiter.api.Test;

class StoryAuthorPhotosTest {
  @Test
  void missingPhotoNeverReadsStorageAndCorruptBytesAreRejected() {
    var repository = mock(StoryRepository.class);
    var storage = mock(ObjectStorage.class);
    var service = new StoryAuthorPhotos(repository, storage);
    var id = UUID.randomUUID();
    when(repository.authorPhoto(id)).thenReturn(Optional.empty());
    assertEquals(
        StoryException.Reason.NOT_FOUND,
        assertThrows(StoryException.class, () -> service.metadata(id)).reason());
    verifyNoInteractions(storage);
    byte[] bytes = {1, 2, 3};
    var photo = new AuthorPhoto("private/key", ImageDigest.sha256(bytes));
    when(storage.get(photo.objectKey())).thenReturn(bytes);
    assertArrayEquals(bytes, service.read(photo));
    when(storage.get(photo.objectKey())).thenReturn(new byte[] {9});
    assertEquals(
        MediaException.Reason.UNAVAILABLE,
        assertThrows(MediaException.class, () -> service.read(photo)).reason());
  }
}
