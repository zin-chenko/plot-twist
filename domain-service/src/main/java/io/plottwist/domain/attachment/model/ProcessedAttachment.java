package io.plottwist.domain.attachment.model;

import java.nio.file.Path;

public record ProcessedAttachment(
    Path file,
    Attachment.Kind kind,
    String contentType,
    int width,
    int height,
    Double durationSeconds) {}
