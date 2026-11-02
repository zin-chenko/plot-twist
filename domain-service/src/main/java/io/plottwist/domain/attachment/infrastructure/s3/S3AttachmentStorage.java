package io.plottwist.domain.attachment.infrastructure.s3;

import io.plottwist.domain.attachment.application.*;
import io.plottwist.domain.attachment.application.port.AttachmentStorage;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@Component
public class S3AttachmentStorage implements AttachmentStorage {
  private final S3Client s3;
  private final String bucket;

  public S3AttachmentStorage(S3Client s3, @Value("${storage.bucket}") String bucket) {
    this.s3 = s3;
    this.bucket = bucket;
  }

  public void put(String key, Path file, String type, String sha) {
    try {
      s3.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(key)
              .contentType(type)
              .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha)))
              .overrideConfiguration(
                  c ->
                      c.apiCallTimeout(Duration.ofMinutes(2))
                          .apiCallAttemptTimeout(Duration.ofSeconds(90)))
              .build(),
          RequestBody.fromFile(file));
    } catch (software.amazon.awssdk.core.exception.SdkException error) {
      throw unavailable();
    }
  }

  public Read open(String key, long start, long end) {
    try {
      var input =
          s3.getObject(
              GetObjectRequest.builder()
                  .bucket(bucket)
                  .key(key)
                  .range("bytes=" + start + "-" + end)
                  .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(15)))
                  .build());
      return new Read() {
        private boolean aborted;

        public InputStream stream() {
          return input;
        }

        public void abort() {
          aborted = true;
          input.abort();
        }

        public void close() throws java.io.IOException {
          if (!aborted) input.close();
        }
      };
    } catch (software.amazon.awssdk.core.exception.SdkException error) {
      throw unavailable();
    }
  }

  public void delete(String key) {
    try {
      s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (software.amazon.awssdk.core.exception.SdkException error) {
      throw unavailable();
    }
  }

  private AttachmentException unavailable() {
    return new AttachmentException(
        AttachmentException.Reason.UNAVAILABLE, "Attachment storage is temporarily unavailable");
  }
}
