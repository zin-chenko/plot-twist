package io.plottwist.domain.attachment.model;

import java.util.UUID;

public record StoredAttachment(Attachment media, UUID owner, String objectKey, String sha256) {}
