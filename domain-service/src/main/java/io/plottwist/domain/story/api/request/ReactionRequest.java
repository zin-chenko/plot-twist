package io.plottwist.domain.story.api.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;

public record ReactionRequest(
    @NotNull @Min(-1) @Max(1) @JsonDeserialize(using = StrictReactionValue.class) Integer value) {}
