package io.plottwist.domain.story.api.request;

import io.plottwist.domain.story.model.StoryContent;
import jakarta.validation.constraints.*;

public record StoryRequest(
    @NotBlank @Size(max = 160) String title, @NotBlank @Size(max = 20000) String body) {
  public StoryContent content() {
    return new StoryContent(title, body);
  }
}
