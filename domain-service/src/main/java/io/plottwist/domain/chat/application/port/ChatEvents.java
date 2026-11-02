package io.plottwist.domain.chat.application.port;

import java.util.UUID;

public interface ChatEvents {
  void changed(UUID chat);
}
