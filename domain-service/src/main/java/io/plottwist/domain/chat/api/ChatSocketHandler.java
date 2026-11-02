package io.plottwist.domain.chat.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.plottwist.domain.chat.application.*;
import io.plottwist.domain.chat.application.port.ChatLiveUpdates;
import io.plottwist.domain.chat.model.ChatChange;
import io.plottwist.domain.config.AccountIdentity;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;

@Component
public class ChatSocketHandler extends TextWebSocketHandler implements ChatLiveUpdates {
  private static final CloseStatus EXPIRED = new CloseStatus(4401, "Session expired");
  private final ChatService chats;
  private final JwtDecoder decoder;
  private final ObjectMapper json;
  private final MeterRegistry metrics;
  private final ConcurrentMap<String, Connection> sessions = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, ConcurrentMap<String, Connection>> users =
      new ConcurrentHashMap<>();
  private final ExecutorService delivery = Executors.newVirtualThreadPerTaskExecutor();
  private final Semaphore sends = new Semaphore(256);
  private final Semaphore connectionSlots = new Semaphore(5000);
  private volatile boolean ready;

  private static final class Connection {
    final WebSocketSession socket;
    final Instant connectedAt = Instant.now();
    volatile Instant lastSeen = connectedAt, expires;
    volatile UUID account;
    boolean writable;
    long window = System.nanoTime();
    int commands;

    Connection(WebSocketSession socket) {
      this.socket = new ConcurrentWebSocketSessionDecorator(socket, 10000, 65536);
    }
  }

  public record Command(
      String type,
      UUID requestId,
      UUID chatId,
      UUID clientMessageId,
      String body,
      Long throughSequence,
      String token) {}

  public ChatSocketHandler(
      ChatService chats, JwtDecoder decoder, ObjectMapper json, MeterRegistry metrics) {
    this.chats = chats;
    this.decoder = decoder;
    this.json = json;
    this.metrics = metrics;
    metrics.gauge("chat.websocket.connections", sessions, Map::size);
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
    if (!connectionSlots.tryAcquire()) {
      socket.close(CloseStatus.SERVICE_OVERLOAD);
      return;
    }
    socket.setTextMessageSizeLimit(32768);
    if (socket
        instanceof org.springframework.web.socket.adapter.NativeWebSocketSession nativeSocket) {
      var nativeSession = nativeSocket.getNativeSession(jakarta.websocket.Session.class);
      if (nativeSession != null)
        nativeSession
            .getUserProperties()
            .put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 10000L);
    }
    sessions.put(socket.getId(), new Connection(socket));
  }

  @Override
  protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
    var connection = sessions.get(socket.getId());
    if (connection == null) return;
    try {
      if (message.getPayloadLength() > 32768) {
        close(connection, CloseStatus.TOO_BIG_TO_PROCESS);
        return;
      }
      if (System.nanoTime() - connection.window > 1_000_000_000L) {
        connection.window = System.nanoTime();
        connection.commands = 0;
      }
      if (++connection.commands > 30) {
        close(connection, CloseStatus.POLICY_VIOLATION);
        return;
      }
      var command = json.readValue(message.getPayload(), Command.class);
      connection.lastSeen = Instant.now();
      if (connection.account == null) {
        authenticate(connection, command);
        return;
      }
      if (!connection.expires.isAfter(Instant.now())) {
        close(connection, EXPIRED);
        return;
      }
      if (!ready) {
        close(connection, CloseStatus.SERVICE_RESTARTED);
        return;
      }
      if ("ping".equals(command.type())) {
        send(connection, Map.of("type", "pong"));
        return;
      }
      if (!connection.writable) {
        error(connection, command.requestId(), 403, "Write scope required");
        return;
      }
      if (command.requestId() == null || command.chatId() == null) {
        close(connection, CloseStatus.BAD_DATA);
        return;
      }
      try {
        switch (command.type() == null ? "" : command.type()) {
          case "send" -> {
            var sent =
                chats.send(
                    command.chatId(),
                    connection.account,
                    command.clientMessageId(),
                    command.body());
            send(
                connection,
                Map.of("type", "ack", "requestId", command.requestId(), "message", sent.message()));
          }
          case "read" -> {
            if (command.throughSequence() == null) {
              error(connection, command.requestId(), 400, "Read position required");
              return;
            }
            var summary =
                chats.read(command.chatId(), connection.account, command.throughSequence());
            send(
                connection,
                Map.of("type", "ack", "requestId", command.requestId(), "summary", summary));
          }
          default -> error(connection, command.requestId(), 400, "Unknown command");
        }
      } catch (ChatException invalid) {
        error(
            connection,
            command.requestId(),
            switch (invalid.reason()) {
              case NOT_FOUND -> 404;
              case INVALID -> 400;
              case CONFLICT -> 409;
            },
            invalid.getMessage());
      } catch (RuntimeException unavailable) {
        error(connection, command.requestId(), 503, "Chat is temporarily unavailable");
      }
    } catch (Exception invalid) {
      close(connection, CloseStatus.BAD_DATA);
    }
  }

  private void authenticate(Connection c, Command command) {
    try {
      if (!"auth".equals(command.type())
          || command.token() == null
          || command.token().length() > 8192) throw new IllegalArgumentException();
      Jwt jwt = decoder.decode(command.token());
      UUID account = AccountIdentity.require(jwt);
      var authorities =
          new org.springframework.security.oauth2.server.resource.authentication
                  .JwtGrantedAuthoritiesConverter()
              .convert(jwt);
      var scopes =
          authorities.stream()
              .map(org.springframework.security.core.GrantedAuthority::getAuthority)
              .collect(java.util.stream.Collectors.toSet());
      if (!scopes.contains("SCOPE_api.read")
          || jwt.getExpiresAt() == null
          || !jwt.getExpiresAt().isAfter(Instant.now())) throw new IllegalArgumentException();
      c.expires = jwt.getExpiresAt();
      c.writable = scopes.contains("SCOPE_api.write");
      ConcurrentMap<String, Connection> connections;
      // Keep the registration atomic with account cleanup and the per-account cap.
      synchronized (users) {
        connections = users.computeIfAbsent(account, id -> new ConcurrentHashMap<>());
        if (connections.size() >= 8) {
          close(c, CloseStatus.SERVICE_OVERLOAD);
          return;
        }
        c.account = account;
        connections.put(c.socket.getId(), c);
      }
      if (!ready) {
        close(c, CloseStatus.SERVICE_RESTARTED);
        return;
      }
      send(c, Map.of("type", "ready"));
      metrics.counter("chat.websocket.authenticated").increment();
    } catch (BadJwtException
        | IllegalArgumentException
        | org.springframework.web.server.ResponseStatusException invalid) {
      close(c, EXPIRED);
    } catch (JwtException unavailable) {
      close(c, CloseStatus.SERVICE_RESTARTED);
    } catch (RuntimeException unavailable) {
      close(c, CloseStatus.SERVICE_RESTARTED);
    }
  }

  private void error(Connection c, UUID requestId, int status, String detail) {
    if (requestId == null) {
      close(c, CloseStatus.BAD_DATA);
      return;
    }
    send(c, Map.of("type", "error", "requestId", requestId, "status", status, "detail", detail));
  }

  private void send(Connection c, Object value) {
    try {
      c.socket.sendMessage(new TextMessage(json.writeValueAsString(value)));
    } catch (Exception failure) {
      close(c, CloseStatus.SESSION_NOT_RELIABLE);
    }
  }

  @Override
  public void changed(ChatChange change) {
    for (UUID recipient : change.recipients()) {
      var connections = users.get(recipient);
      if (connections == null) continue;
      for (var c : connections.values()) {
        if (!c.expires.isAfter(Instant.now())) {
          close(c, EXPIRED);
          continue;
        }
        if (!sends.tryAcquire()) {
          close(c, CloseStatus.SERVICE_OVERLOAD);
          continue;
        }
        try {
          delivery.submit(
              () -> {
                try {
                  send(c, Map.of("type", "changed", "chatId", change.chatId()));
                } finally {
                  sends.release();
                }
              });
        } catch (RejectedExecutionException stopped) {
          sends.release();
          close(c, CloseStatus.SERVICE_RESTARTED);
        }
      }
    }
  }

  @Override
  public void transportReady(boolean available) {
    ready = available;
    // A recovered consumer has a fresh exclusive queue: force REST catch-up on every client.
    sessions.values().forEach(c -> close(c, CloseStatus.SERVICE_RESTARTED));
  }

  @Scheduled(fixedDelay = 1000)
  public void reap() {
    Instant now = Instant.now();
    sessions
        .values()
        .forEach(
            c -> {
              if (c.account == null && c.connectedAt.plusSeconds(10).isBefore(now))
                close(c, CloseStatus.POLICY_VIOLATION);
              else if (c.expires != null && !c.expires.isAfter(now)) close(c, EXPIRED);
              else if (c.lastSeen.plusSeconds(75).isBefore(now))
                close(c, CloseStatus.SESSION_NOT_RELIABLE);
            });
  }

  private void close(Connection c, CloseStatus status) {
    remove(c);
    try {
      c.socket.close(status);
    } catch (Exception ignored) {
    }
  }

  private void remove(Connection c) {
    if (sessions.remove(c.socket.getId(), c)) connectionSlots.release();
    UUID account = c.account;
    if (account != null)
      synchronized (users) {
        var connections = users.get(account);
        if (connections != null) {
          connections.remove(c.socket.getId(), c);
          if (connections.isEmpty()) users.remove(account, connections);
        }
      }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    var c = sessions.get(session.getId());
    if (c != null) remove(c);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    var c = sessions.get(session.getId());
    if (c != null) close(c, CloseStatus.SESSION_NOT_RELIABLE);
  }

  @PreDestroy
  void stop() {
    ready = false;
    sessions.values().forEach(c -> close(c, CloseStatus.GOING_AWAY));
    delivery.shutdownNow();
  }
}
