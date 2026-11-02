package io.plottwist.domain.chat.application.port;

import io.plottwist.domain.chat.model.ChatChange;

public interface ChatLiveUpdates {
  void changed(ChatChange change);

  void transportReady(boolean ready);
}
