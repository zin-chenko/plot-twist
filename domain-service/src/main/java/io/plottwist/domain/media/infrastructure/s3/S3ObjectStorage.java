package io.plottwist.domain.media.infrastructure.s3;

import io.plottwist.domain.media.application.MediaException;
import io.plottwist.domain.media.application.port.ObjectStorage;
import io.plottwist.domain.media.application.port.PhotoProcessor;
import io.plottwist.domain.media.model.ImageDigest;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@Component
public class S3ObjectStorage implements ObjectStorage {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(S3ObjectStorage.class);
  private final S3Client client;
  private final String bucket;

  public S3ObjectStorage(S3Client client, @Value("${storage.bucket}") String bucket) {
    this.client = client;
    this.bucket = bucket;
  }

  public void put(String key, byte[] bytes) {
    try {
      client.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(key)
              .contentType("image/jpeg")
              .checksumAlgorithm(ChecksumAlgorithm.SHA256)
              .checksumSHA256(
                  java.util.Base64.getEncoder()
                      .encodeToString(java.util.HexFormat.of().parseHex(ImageDigest.sha256(bytes))))
              .build(),
          RequestBody.fromBytes(bytes));
    } catch (software.amazon.awssdk.core.exception.SdkException error) {
      report(error);
      throw unavailable();
    }
  }

  public byte[] get(String key) {
    try (var input = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
      byte[] bytes = input.readNBytes(PhotoProcessor.MAX_OUTPUT + 1);
      if (bytes.length > PhotoProcessor.MAX_OUTPUT) throw unavailable();
      return bytes;
    } catch (software.amazon.awssdk.core.exception.SdkException | IOException unavailable) {
      throw unavailable();
    }
  }

  public void delete(String key) {
    try {
      client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (software.amazon.awssdk.core.exception.SdkException error) {
      throw unavailable();
    }
  }

  public void check() {
    client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
  }

  private static void report(software.amazon.awssdk.core.exception.SdkException error) {
    if (error instanceof S3Exception s3) {
      String code = s3.awsErrorDetails() == null ? "unknown" : s3.awsErrorDetails().errorCode();
      log.warn("Photo storage rejected request: status={} code={}", s3.statusCode(), code);
    } else
      log.warn(
          "Photo storage transport failure: {}",
          error.getCause() == null
              ? error.getClass().getSimpleName()
              : error.getCause().getClass().getSimpleName());
  }

  private MediaException unavailable() {
    return new MediaException(
        MediaException.Reason.UNAVAILABLE, "Photo storage is temporarily unavailable");
  }
}
