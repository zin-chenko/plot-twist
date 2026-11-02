package io.plottwist.domain.attachment.application.port;

import io.plottwist.domain.attachment.model.*;
import java.util.*;

public interface AttachmentRepository {
  void reserve(StoredAttachment file);

  boolean ready(UUID id);

  Optional<StoredAttachment> accessible(UUID id, UUID viewer);

  boolean requestDelete(UUID id, UUID owner);
}
