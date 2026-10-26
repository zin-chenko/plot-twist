package io.plottwist.domain.media.application.port;

public interface ObjectStorage {
  void put(String key, byte[] bytes);

  byte[] get(String key);

  void delete(String key);

  void check();
}
