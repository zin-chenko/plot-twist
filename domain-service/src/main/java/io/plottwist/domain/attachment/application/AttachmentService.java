package io.plottwist.domain.attachment.application;

import io.plottwist.domain.attachment.application.port.*;
import io.plottwist.domain.attachment.model.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

@Service
public class AttachmentService {
  private final AttachmentProcessor processor;
  private final AttachmentRepository repository;
  private final AttachmentStorage storage;
  private final Semaphore uploads = new Semaphore(1);

  public AttachmentService(
      AttachmentProcessor processor, AttachmentRepository repository, AttachmentStorage storage) {
    this.processor = processor;
    this.repository = repository;
    this.storage = storage;
  }

  public Attachment upload(UUID owner, InputStream input, long length) {
    if (length > AttachmentProcessor.MAX_FILE)
      throw new AttachmentException(
          AttachmentException.Reason.TOO_LARGE, "Maximum file size is 50 MiB");
    if (!uploads.tryAcquire())
      throw new AttachmentException(
          AttachmentException.Reason.BUSY, "Media processor is busy; retry later");
    Path directory = null;
    try {
      directory = Files.createTempDirectory("story-media-");
      var source = directory.resolve("input.bin");
      try (var out = Files.newOutputStream(source)) {
        byte[] buffer = new byte[65536];
        long size = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
          size += count;
          if (size > AttachmentProcessor.MAX_FILE)
            throw new AttachmentException(
                AttachmentException.Reason.TOO_LARGE, "Maximum file size is 50 MiB");
          out.write(buffer, 0, count);
        }
        if (size == 0)
          throw new AttachmentException(AttachmentException.Reason.INVALID, "File is empty");
      }
      var processed = processor.process(source, directory);
      long size = Files.size(processed.file());
      if (size <= 0 || size > AttachmentProcessor.MAX_FILE)
        throw new AttachmentException(
            AttachmentException.Reason.TOO_LARGE, "Processed file exceeds the size limit");
      var id = UUID.randomUUID();
      var metadata =
          new Attachment(
              id,
              processed.kind(),
              processed.contentType(),
              size,
              processed.width(),
              processed.height(),
              processed.durationSeconds());
      var digest = MessageDigest.getInstance("SHA-256");
      try (var bytes = Files.newInputStream(processed.file())) {
        byte[] buffer = new byte[65536];
        int n;
        while ((n = bytes.read(buffer)) != -1) digest.update(buffer, 0, n);
      }
      var stored =
          new StoredAttachment(
              metadata,
              owner,
              "stories/" + owner + "/" + id,
              HexFormat.of().formatHex(digest.digest()));
      repository.reserve(stored);
      storage.put(stored.objectKey(), processed.file(), metadata.contentType(), stored.sha256());
      if (!repository.ready(id))
        throw new AttachmentException(
            AttachmentException.Reason.UNAVAILABLE, "Upload state changed; retry later");
      return metadata;
    } catch (IOException error) {
      throw new AttachmentException(
          AttachmentException.Reason.INVALID, "Could not read the media file");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    } finally {
      if (directory != null)
        try (var paths = Files.walk(directory)) {
          paths
              .sorted(Comparator.reverseOrder())
              .forEach(
                  p -> {
                    try {
                      Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                  });
        } catch (IOException ignored) {
        }
      uploads.release();
    }
  }

  public StoredAttachment accessible(UUID id, UUID viewer) {
    return repository
        .accessible(id, viewer)
        .orElseThrow(
            () ->
                new AttachmentException(
                    AttachmentException.Reason.NOT_FOUND, "Attachment not found"));
  }

  public void remove(UUID id, UUID owner) {
    if (!repository.requestDelete(id, owner))
      throw new AttachmentException(AttachmentException.Reason.NOT_FOUND, "Attachment not found");
  }
}
