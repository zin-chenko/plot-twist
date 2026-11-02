package io.plottwist.domain.attachment.infrastructure.config;

import io.plottwist.domain.attachment.application.TicketSigner;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;

@Configuration
public class AttachmentConfig {
  public static final String LOCAL_KEY =
      "plot-twist-local-story-media-key-change-before-production";

  @Bean
  TicketSigner ticketSigner(
      @Value("${STORY_MEDIA_SIGNING_KEY:" + LOCAL_KEY + "}") String key, Environment env) {
    if (env.acceptsProfiles(Profiles.of("prod")) && key.equals(LOCAL_KEY))
      throw new IllegalStateException("Production requires STORY_MEDIA_SIGNING_KEY");
    return new TicketSigner(key, Clock.systemUTC());
  }
}
