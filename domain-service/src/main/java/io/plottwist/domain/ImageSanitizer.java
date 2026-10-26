package io.plottwist.domain;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ImageSanitizer {
    public static final int MAX_INPUT = 5 * 1024 * 1024;
    public static final int MAX_OUTPUT = 1024 * 1024;
    private final Semaphore decoders = new Semaphore(2);

    public Image sanitize(byte[] bytes) {
        if (bytes.length == 0) throw bad("Image is empty");
        if (bytes.length > MAX_INPUT) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Maximum image size is 5 MiB");
        if (!decoders.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Image processor is busy; retry later");
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only JPEG and PNG images are accepted");
            var reader = readers.next();
            try {
                if (!Set.of("JPEG", "PNG").contains(reader.getFormatName().toUpperCase(java.util.Locale.ROOT)))
                    throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only JPEG and PNG images are accepted");
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 16_000_000)
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Maximum decoded size is 16 megapixels");
                var source = reader.read(0);
                double scale = Math.min(1.0, 1024.0 / Math.max(width, height));
                var image = new BufferedImage(Math.max(1, (int)(width * scale)), Math.max(1, (int)(height * scale)), BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.drawImage(source, 0, 0, image.getWidth(), image.getHeight(), null);
                } finally { graphics.dispose(); source.flush(); }
                var output = new ByteArrayOutputStream();
                if (!ImageIO.write(image, "JPEG", output)) throw new IllegalStateException("JPEG encoder missing");
                image.flush();
                byte[] sanitized = output.toByteArray();
                if (sanitized.length > MAX_OUTPUT) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image is too complex; resize it first");
                return new Image(sanitized, digest(sanitized));
            } finally { reader.dispose(); }
        } catch (IOException malformed) { throw bad("Malformed image"); }
        finally { decoders.release(); }
    }

    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ResponseStatusException bad(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
    public record Image(byte[] bytes, String sha256) {
        @Override public String toString() { return "Image[redacted]"; }
    }
}
