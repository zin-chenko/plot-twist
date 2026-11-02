package io.plottwist.domain.attachment.infrastructure.processing;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.plottwist.domain.attachment.application.AttachmentException;
import io.plottwist.domain.attachment.model.Attachment;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

class FileAttachmentProcessorTest {
  @TempDir Path directory;

  private FileAttachmentProcessor processor() {
    return new FileAttachmentProcessor("ffmpeg", "ffprobe", new ObjectMapper());
  }

  @Test
  void rewritesPngAndRejectsActiveDocumentsOrTruncatedFiles() throws Exception {
    var image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, 0xff112233);
    var input = directory.resolve("input.png");
    ImageIO.write(image, "PNG", input.toFile());
    var output = processor().process(input, directory);
    assertEquals(Attachment.Kind.IMAGE, output.kind());
    assertEquals(40, output.width());
    assertEquals(20, output.height());
    assertEquals(0xff112233, ImageIO.read(output.file().toFile()).getRGB(0, 0));
    Files.writeString(input, "<svg onload=alert(1)></svg>");
    assertThrows(AttachmentException.class, () -> processor().process(input, directory));
    Files.write(input, new byte[] {(byte) 255, (byte) 216, 0, 0, 0, 0, 0, 0});
    assertThrows(AttachmentException.class, () -> processor().process(input, directory));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "STORY_MEDIA_PROCESSOR_TEST", matches = "true")
  void gifRemainsAnimatedAfterProcessing() throws Exception {
    var input = directory.resolve("input.gif");
    var writer = ImageIO.getImageWritersByFormatName("gif").next();
    try (var stream = ImageIO.createImageOutputStream(input.toFile())) {
      writer.setOutput(stream);
      writer.prepareWriteSequence(null);
      for (int i = 0; i < 2; i++) {
        var frame = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        var g = frame.createGraphics();
        g.setColor(i == 0 ? java.awt.Color.RED : java.awt.Color.BLUE);
        g.fillRect(0, 0, 32, 32);
        g.dispose();
        var metadata =
            writer.getDefaultImageMetadata(
                ImageTypeSpecifier.createFromRenderedImage(frame), writer.getDefaultWriteParam());
        var tree = metadata.getAsTree("javax_imageio_gif_image_1.0");
        for (var node = tree.getFirstChild(); node != null; node = node.getNextSibling())
          if (node.getNodeName().equals("GraphicControlExtension"))
            ((javax.imageio.metadata.IIOMetadataNode) node).setAttribute("delayTime", "10");
        metadata.setFromTree("javax_imageio_gif_image_1.0", tree);
        writer.writeToSequence(new IIOImage(frame, null, metadata), null);
      }
      writer.endWriteSequence();
    } finally {
      writer.dispose();
    }
    var result = processor().process(input, directory);
    assertEquals(Attachment.Kind.GIF, result.kind());
    try (var stream = ImageIO.createImageInputStream(result.file().toFile())) {
      var reader = ImageIO.getImageReadersByFormatName("gif").next();
      try {
        reader.setInput(stream);
        assertTrue(reader.getNumImages(true) >= 2);
      } finally {
        reader.dispose();
      }
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "STORY_MEDIA_PROCESSOR_TEST", matches = "true")
  void acceptsRealMp4AndWebmAndChecksDuration() throws Exception {
    for (boolean mp4 : List.of(true, false)) {
      var input = directory.resolve(mp4 ? "fixture.mp4" : "fixture.webm");
      var command =
          new ArrayList<>(
              List.of(
                  "ffmpeg",
                  "-nostdin",
                  "-v",
                  "error",
                  "-f",
                  "lavfi",
                  "-i",
                  "testsrc2=size=64x64:rate=10",
                  "-t",
                  "0.5",
                  "-threads",
                  "1",
                  "-c:v",
                  mp4 ? "libx264" : "libvpx-vp9",
                  "-an",
                  "-y",
                  input.toString()));
      var p = new ProcessBuilder(command).redirectErrorStream(true).start();
      assertEquals(0, p.waitFor(), new String(p.getInputStream().readAllBytes()));
      var result = processor().process(input, directory);
      assertEquals(Attachment.Kind.VIDEO, result.kind());
      assertEquals(mp4 ? "video/mp4" : "video/webm", result.contentType());
      assertEquals(64, result.width());
      assertTrue(result.durationSeconds() > 0);
      assertTrue(Files.size(result.file()) > 100);
    }
  }
}
