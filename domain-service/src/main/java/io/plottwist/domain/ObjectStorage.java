package io.plottwist.domain;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@Component
public class ObjectStorage {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ObjectStorage.class);
    private final S3Client client;
    private final String bucket;

    ObjectStorage(S3Client client, @Value("${storage.bucket}") String bucket) {
        this.client = client; this.bucket = bucket;
    }

    void put(String key, byte[] bytes) {
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("image/jpeg").checksumAlgorithm(ChecksumAlgorithm.SHA256)
                .checksumSHA256(java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(ImageSanitizer.digest(bytes))))
                .build(), RequestBody.fromBytes(bytes));
        } catch (software.amazon.awssdk.core.exception.SdkException error) { report(error); throw unavailable(); }
    }

    byte[] get(String key) {
        try (var input = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            byte[] bytes = input.readNBytes(ImageSanitizer.MAX_OUTPUT + 1);
            if (bytes.length > ImageSanitizer.MAX_OUTPUT) throw unavailable();
            return bytes;
        } catch (software.amazon.awssdk.core.exception.SdkException | IOException unavailable) { throw unavailable(); }
    }

    void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    void check() { client.headBucket(HeadBucketRequest.builder().bucket(bucket).build()); }

    private static void report(software.amazon.awssdk.core.exception.SdkException error) {
        if (error instanceof S3Exception s3) {
            String code = s3.awsErrorDetails() == null ? "unknown" : s3.awsErrorDetails().errorCode();
            log.warn("Photo storage rejected request: status={} code={}", s3.statusCode(), code);
        } else log.warn("Photo storage transport failure: {}", error.getCause() == null ? error.getClass().getSimpleName() : error.getCause().getClass().getSimpleName());
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Photo storage is temporarily unavailable");
    }
}
