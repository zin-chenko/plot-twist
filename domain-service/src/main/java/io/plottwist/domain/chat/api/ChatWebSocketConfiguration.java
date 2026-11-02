package io.plottwist.domain.chat.api;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "chat.live.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ChatWebSocketConfiguration implements WebSocketConfigurer {
  private final ChatSocketHandler handler;
  private final String[] origins;

  public ChatWebSocketConfiguration(
      ChatSocketHandler handler,
      @Value(
              "${chat.websocket.allowed-origins:http://localhost:4200,http://127.0.0.1:4200,http://localhost:8080,http://127.0.0.1:8080}")
          String origins) {
    this.handler = handler;
    this.origins = origins.split(",");
    for (String origin : this.origins) {
      var uri = java.net.URI.create(origin);
      if (!Set.of("http", "https").contains(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || uri.getRawPath() != null && !uri.getRawPath().isEmpty())
        throw new IllegalArgumentException("Configure exact chat browser origins without paths");
    }
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(handler, "/ws/chats")
        .setAllowedOrigins(origins)
        .addInterceptors(
            new HandshakeInterceptor() {
              public boolean beforeHandshake(
                  ServerHttpRequest request,
                  ServerHttpResponse response,
                  WebSocketHandler handler,
                  Map<String, Object> attributes) {
                // Browser token is accepted only in the first frame, never in a logged URL.
                if (request.getURI().getRawQuery() != null
                    || !Arrays.asList(origins).contains(request.getHeaders().getOrigin())) {
                  response.setStatusCode(HttpStatus.FORBIDDEN);
                  return false;
                }
                return true;
              }

              public void afterHandshake(
                  ServerHttpRequest request,
                  ServerHttpResponse response,
                  WebSocketHandler handler,
                  Exception exception) {}
            });
  }

  @Bean
  ServletServerContainerFactoryBean chatSocketContainer() {
    var factory = new ServletServerContainerFactoryBean();
    factory.setMaxTextMessageBufferSize(32768);
    factory.setMaxBinaryMessageBufferSize(1024);
    factory.setMaxSessionIdleTimeout(80000L);
    factory.setAsyncSendTimeout(10000L);
    return factory;
  }
}
