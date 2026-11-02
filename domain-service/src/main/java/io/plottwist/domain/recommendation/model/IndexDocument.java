package io.plottwist.domain.recommendation.model;

import java.util.UUID;

public record IndexDocument(UUID id, UUID author, long version, String title, String body) {}
