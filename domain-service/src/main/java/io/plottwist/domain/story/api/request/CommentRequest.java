package io.plottwist.domain.story.api.request;

import jakarta.validation.constraints.*;

public record CommentRequest(@NotBlank @Size(max = 2000) String body) {}
