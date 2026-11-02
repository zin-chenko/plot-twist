package io.plottwist.domain.attachment.application.port;

import io.plottwist.domain.attachment.model.ProcessedAttachment;
import java.nio.file.Path;

public interface AttachmentProcessor {
  long MAX_FILE = 50L * 1024 * 1024;

  ProcessedAttachment process(Path input, Path directory);
}
