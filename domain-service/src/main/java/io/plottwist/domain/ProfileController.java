package io.plottwist.domain;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {
    private final ProfileService profiles;
    private final ImageSanitizer images;

    ProfileController(ProfileService profiles, ImageSanitizer images) { this.profiles = profiles; this.images = images; }

    static UUID owner(Jwt jwt) {
        String account = jwt.getClaimAsString("account_id");
        if (account == null || !account.equals(jwt.getSubject())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A verified user account is required");
        try { return UUID.fromString(account); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid account identity"); }
    }

    static long version(String value) {
        if (value == null) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "If-Match is required");
        if (!value.matches("\"[0-9]{1,18}\"")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match must contain a quoted numeric ETag");
        return Long.parseLong(value.substring(1, value.length() - 1));
    }

    static ResponseEntity<Profile> response(Profile profile) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\"" + profile.version() + "\"").body(profile);
    }

    @GetMapping
    ResponseEntity<Profile> get(@AuthenticationPrincipal Jwt jwt) { return response(profiles.get(owner(jwt))); }

    @PutMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Profile> save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Profile.Input body,
            @RequestHeader(value="If-Match", required=false) String match,
            @RequestHeader(value="If-None-Match", required=false) String noneMatch) {
        boolean create = "*".equals(noneMatch);
        if (noneMatch != null && !create || create && match != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use either If-None-Match: * or If-Match");
        Profile profile = profiles.save(owner(jwt), body, create ? null : version(match), create);
        if (create) return ResponseEntity.status(HttpStatus.CREATED).location(java.net.URI.create("/api/v1/profile"))
            .cacheControl(CacheControl.noStore()).eTag(Character.toString('"') + profile.version() + '"').body(profile);
        return response(profile);
    }

    @PutMapping(value="/photo", consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<Profile> upload(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
            @RequestHeader(value="If-Match", required=false) String match) throws IOException {
        UUID owner = owner(jwt);
        long expected = version(match);
        profiles.checkVersion(owner, expected);
        byte[] body = request.getInputStream().readNBytes(ImageSanitizer.MAX_INPUT + 1);
        return response(profiles.upload(owner, expected, images.sanitize(body)));
    }

    @GetMapping("/photo")
    ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value="If-None-Match", required=false) String match) {
        UUID owner = owner(jwt);
        Profile profile = profiles.get(owner);
        if (profile.photo() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found");
        String etag = "\"" + profile.photo().sha256() + "\"";
        if (etag.equals(match)) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=avatar.jpg")
            .header("X-Content-Type-Options", "nosniff").eTag(etag).body(profiles.photo(owner, profile));
    }

    @DeleteMapping
    ResponseEntity<Void> deleteProfile(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value="If-Match", required=false) String match) {
        profiles.delete(owner(jwt), version(match));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @DeleteMapping("/photo")
    ResponseEntity<Profile> remove(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value="If-Match", required=false) String match) {
        return response(profiles.removePhoto(owner(jwt), version(match)));
    }
}
