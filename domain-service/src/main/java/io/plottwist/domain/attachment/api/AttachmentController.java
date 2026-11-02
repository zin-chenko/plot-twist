package io.plottwist.domain.attachment.api;

import io.plottwist.domain.attachment.application.*;
import io.plottwist.domain.attachment.application.port.*;
import io.plottwist.domain.attachment.model.*;
import io.plottwist.domain.config.AccountIdentity;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/story-media")
public class AttachmentController {
  private final AttachmentService media;
  private final TicketSigner tickets;
  private final AttachmentStorage storage;
  private final Semaphore downloads = new Semaphore(12);

  public AttachmentController(
      AttachmentService media, TicketSigner tickets, AttachmentStorage storage) {
    this.media = media;
    this.tickets = tickets;
    this.storage = storage;
  }

  @PostMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  ResponseEntity<Attachment> upload(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request)
      throws IOException {
    var asset =
        media.upload(
            AccountIdentity.require(jwt), request.getInputStream(), request.getContentLengthLong());
    return ResponseEntity.created(URI.create("/api/v1/story-media/" + asset.id() + "/access"))
        .cacheControl(CacheControl.noStore())
        .body(asset);
  }

  @GetMapping("/{id}/access")
  ResponseEntity<Access> access(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    var viewer = AccountIdentity.require(jwt);
    var asset = media.accessible(id, viewer);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new Access(
                asset.media(),
                "/api/v1/story-media/" + id + "/content?ticket=" + tickets.issue(id, viewer),
                Instant.now().plusSeconds(TicketSigner.TTL_SECONDS)));
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    media.remove(id, AccountIdentity.require(jwt));
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  @RequestMapping(
      value = "/{id}/content",
      method = {RequestMethod.GET, RequestMethod.HEAD})
  void content(
      @PathVariable UUID id,
      @RequestParam(required = false) String ticket,
      @RequestHeader(value = "Range", required = false) String requested,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    var viewer = tickets.verify(id, ticket);
    var file = media.accessible(id, viewer);
    if (request.getMethod().equals("HEAD")) {
      response.setStatus(200);
      response.setContentType(file.media().contentType());
      response.setContentLengthLong(file.media().sizeBytes());
      response.setHeader("Accept-Ranges", "bytes");
      response.setHeader("Cache-Control", "private, no-store");
      response.setHeader("X-Content-Type-Options", "nosniff");
      return;
    }
    ByteRange range;
    try {
      range = ByteRange.parse(requested, file.media().sizeBytes());
    } catch (IllegalArgumentException invalid) {
      response.setStatus(416);
      response.setHeader("Content-Range", "bytes */" + file.media().sizeBytes());
      response.setHeader("Cache-Control", "no-store");
      return;
    }
    if (!downloads.tryAcquire())
      throw new AttachmentException(
          AttachmentException.Reason.BUSY, "Download capacity reached; retry later");
    try (var read = storage.open(file.objectKey(), range.start(), range.end())) {
      response.setStatus(range.partial() ? 206 : 200);
      response.setContentType(file.media().contentType());
      response.setContentLengthLong(range.length());
      response.setHeader("Cache-Control", "private, no-store");
      response.setHeader("Accept-Ranges", "bytes");
      response.setHeader("X-Content-Type-Options", "nosniff");
      response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
      response.setHeader("Referrer-Policy", "no-referrer");
      response.setHeader("ETag", "\"" + file.sha256() + "\"");
      response.setHeader(
          "Content-Disposition",
          "inline; filename=attachment." + extension(file.media().contentType()));
      if (range.partial())
        response.setHeader(
            "Content-Range",
            "bytes " + range.start() + "-" + range.end() + "/" + file.media().sizeBytes());
      try {
        byte[] buffer = new byte[65536];
        long remaining = range.length();
        while (remaining > 0) {
          int n = read.stream().read(buffer, 0, (int) Math.min(remaining, buffer.length));
          if (n < 0) throw new IOException("Truncated object response");
          response.getOutputStream().write(buffer, 0, n);
          remaining -= n;
        }
      } catch (IOException disconnected) {
        read.abort();
        throw disconnected;
      }
    } finally {
      downloads.release();
    }
  }

  private static String extension(String type) {
    return switch (type) {
      case "image/jpeg" -> "jpg";
      case "image/png" -> "png";
      case "image/gif" -> "gif";
      case "video/mp4" -> "mp4";
      case "video/webm" -> "webm";
      default -> "bin";
    };
  }

  public record Access(Attachment media, String url, Instant expiresAt) {}
}
