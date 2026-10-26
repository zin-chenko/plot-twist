package io.plottwist.domain.media.infrastructure.image;

import static org.junit.jupiter.api.Assertions.*;

import io.plottwist.domain.media.application.MediaException;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageSanitizerTest {
  private final ImageSanitizer sanitizer = new ImageSanitizer();

  @Test
  void rewritesAndBoundsDecodedImage() throws Exception {
    var source = new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_ARGB);
    var input = new ByteArrayOutputStream();
    ImageIO.write(source, "PNG", input);
    var result = sanitizer.sanitize(input.toByteArray());
    assertEquals(0xff, result.bytes()[0] & 255);
    assertEquals(0xd8, result.bytes()[1] & 255);
    var decoded = ImageIO.read(new ByteArrayInputStream(result.bytes()));
    assertEquals(1024, decoded.getWidth());
    assertEquals(512, decoded.getHeight());
    assertEquals(64, result.sha256().length());
  }

  @Test
  void rejectsActiveContentAndOversizedInput() {
    assertEquals(
        MediaException.Reason.UNSUPPORTED_FORMAT,
        assertThrows(
                MediaException.class,
                () -> sanitizer.sanitize("<svg onload='alert(1)'/>".getBytes()))
            .reason());
    assertEquals(
        MediaException.Reason.TOO_LARGE,
        assertThrows(
                MediaException.class,
                () -> sanitizer.sanitize(new byte[ImageSanitizer.MAX_INPUT + 1]))
            .reason());
  }

  @Test
  void checksPixelCountBeforeAllocatingRaster() throws Exception {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
    var chunk = new ByteArrayOutputStream();
    var header = new DataOutputStream(chunk);
    header.writeBytes("IHDR");
    header.writeInt(50000);
    header.writeInt(50000);
    header.write(new byte[] {8, 2, 0, 0, 0});
    out.writeInt(13);
    out.write(chunk.toByteArray());
    var crc = new CRC32();
    crc.update(chunk.toByteArray());
    out.writeInt((int) crc.getValue());
    out.writeInt(0);
    out.writeBytes("IEND");
    crc.reset();
    crc.update("IEND".getBytes());
    out.writeInt((int) crc.getValue());
    assertEquals(
        MediaException.Reason.TOO_LARGE,
        assertThrows(MediaException.class, () -> sanitizer.sanitize(bytes.toByteArray())).reason());
  }
}
