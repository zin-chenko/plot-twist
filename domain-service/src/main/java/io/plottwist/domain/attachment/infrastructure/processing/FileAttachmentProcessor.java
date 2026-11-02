package io.plottwist.domain.attachment.infrastructure.processing;

import com.fasterxml.jackson.databind.*;
import io.plottwist.domain.attachment.application.AttachmentException;
import io.plottwist.domain.attachment.application.port.AttachmentProcessor;
import io.plottwist.domain.attachment.model.*;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileAttachmentProcessor implements AttachmentProcessor {
  private final String ffmpeg, ffprobe;
  private final ObjectMapper json;

  public FileAttachmentProcessor(
      @Value("${story-media.ffmpeg:ffmpeg}") String ffmpeg,
      @Value("${story-media.ffprobe:ffprobe}") String ffprobe,
      ObjectMapper json) {
    this.ffmpeg = ffmpeg;
    this.ffprobe = ffprobe;
    this.json = json;
  }

  public ProcessedAttachment process(Path source, Path directory) {
    try {
      byte[] header;
      try (var input = Files.newInputStream(source)) {
        header = input.readNBytes(16);
      }
      if (header.length >= 6
          && new String(header, 0, 6, java.nio.charset.StandardCharsets.US_ASCII)
              .matches("GIF8[79]a")) return gif(source, directory);
      if (header.length >= 8
          && ((header[0] & 255) == 137 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
              || (header[0] & 255) == 255 && (header[1] & 255) == 216))
        return image(source, directory);
      if (header.length >= 12
          && header[4] == 'f'
          && header[5] == 't'
          && header[6] == 'y'
          && header[7] == 'p') return video(source, directory, true);
      if (header.length >= 4
          && (header[0] & 255) == 0x1a
          && (header[1] & 255) == 0x45
          && (header[2] & 255) == 0xdf
          && (header[3] & 255) == 0xa3) return video(source, directory, false);
      throw new AttachmentException(
          AttachmentException.Reason.UNSUPPORTED, "Use JPEG, PNG, GIF, MP4 (H.264/AAC), or WebM");
    } catch (AttachmentException known) {
      throw known;
    } catch (RuntimeException invalid) {
      throw invalid("Malformed media file");
    } catch (IOException invalid) {
      throw invalid("Malformed media file");
    }
  }

  private ProcessedAttachment image(Path source, Path directory) throws IOException {
    if (Files.size(source) > 10L * 1024 * 1024) throw large("Images may be up to 10 MiB");
    try (var stream = ImageIO.createImageInputStream(source.toFile())) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) throw invalid("Malformed image");
      var reader = readers.next();
      try {
        reader.setInput(stream, true, true);
        String format = reader.getFormatName().toUpperCase(Locale.ROOT);
        if (!Set.of("JPEG", "PNG").contains(format)) throw invalid("Unsupported image");
        int w = reader.getWidth(0), h = reader.getHeight(0);
        dimensions(w, h, 20_000_000);
        var decoded = reader.read(0);
        double scale = Math.min(1, 2048.0 / Math.max(w, h));
        int width = Math.max(1, (int) (w * scale)), height = Math.max(1, (int) (h * scale));
        boolean png = format.equals("PNG");
        var clean =
            new BufferedImage(
                width, height, png ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var graphics = clean.createGraphics();
        try {
          graphics.setRenderingHint(
              RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
          graphics.drawImage(decoded, 0, 0, width, height, null);
        } finally {
          graphics.dispose();
          decoded.flush();
        }
        var output = directory.resolve(png ? "clean.png" : "clean.jpg");
        try {
          if (!ImageIO.write(clean, png ? "PNG" : "JPEG", output.toFile()))
            throw invalid("Image encoder unavailable");
        } finally {
          clean.flush();
        }
        if (Files.size(output) > 10L * 1024 * 1024)
          throw large("Resize this image before uploading");
        return new ProcessedAttachment(
            output, Attachment.Kind.IMAGE, png ? "image/png" : "image/jpeg", width, height, null);
      } finally {
        reader.dispose();
      }
    }
  }

  private ProcessedAttachment gif(Path source, Path directory) throws IOException {
    if (Files.size(source) > 15L * 1024 * 1024) throw large("GIF files may be up to 15 MiB");
    int width, height;
    double duration = 0;
    try (var stream = ImageIO.createImageInputStream(source.toFile())) {
      var reader = ImageIO.getImageReadersByFormatName("gif").next();
      try {
        reader.setInput(stream, false, false);
        int frames = reader.getNumImages(true);
        if (frames < 1 || frames > 300) throw large("GIF may contain up to 300 frames");
        var screen = reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");
        var node = child(screen, "LogicalScreenDescriptor");
        width =
            Integer.parseInt(
                node.getAttributes().getNamedItem("logicalScreenWidth").getNodeValue());
        height =
            Integer.parseInt(
                node.getAttributes().getNamedItem("logicalScreenHeight").getNodeValue());
        dimensions(width, height, 4_194_304);
        if ((long) width * height * frames > 40_000_000)
          throw large("GIF animation has too many decoded pixels");
        for (int i = 0; i < frames; i++) {
          dimensions(reader.getWidth(i), reader.getHeight(i), 4_194_304);
          var control =
              child(
                  reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0"),
                  "GraphicControlExtension");
          duration +=
              control == null
                  ? 0.1
                  : Math.max(
                          2,
                          Integer.parseInt(
                              control.getAttributes().getNamedItem("delayTime").getNodeValue()))
                      / 100.0;
        }
        if (duration > 60) throw large("GIF duration is limited to 60 seconds");
      } finally {
        reader.dispose();
      }
    }
    var out = directory.resolve("clean.gif");
    run(
        List.of(
            ffmpeg,
            "-nostdin",
            "-max_alloc",
            "134217728",
            "-v",
            "error",
            "-xerror",
            "-max_alloc",
            "134217728",
            "-threads",
            "1",
            "-filter_threads",
            "1",
            "-protocol_whitelist",
            "file,pipe",
            "-f",
            "gif",
            "-ignore_loop",
            "1",
            "-i",
            source.toString(),
            "-vsync",
            "0",
            "-map",
            "0:v:0",
            "-an",
            "-vf",
            "scale=w='min(1280,iw)':h='min(1280,ih)':force_original_aspect_ratio=decrease",
            "-map_metadata",
            "-1",
            "-threads",
            "1",
            "-c:v",
            "gif",
            "-loop",
            "0",
            "-fs",
            "16777216",
            "-y",
            out.toString()),
        25);
    if (Files.size(out) > 15L * 1024 * 1024) throw large("Processed GIF exceeds 15 MiB");
    double scale = Math.min(1, 1280.0 / Math.max(width, height));
    return new ProcessedAttachment(
        out,
        Attachment.Kind.GIF,
        "image/gif",
        Math.max(1, (int) (width * scale)),
        Math.max(1, (int) (height * scale)),
        duration);
  }

  private ProcessedAttachment video(Path source, Path directory, boolean mp4) throws IOException {
    var probe =
        new ArrayList<>(
            List.of(
                ffprobe,
                "-v",
                "error",
                "-max_alloc",
                "134217728",
                "-probesize",
                "8388608",
                "-analyzeduration",
                "5000000",
                "-protocol_whitelist",
                "file,pipe",
                "-f",
                mp4 ? "mov" : "matroska"));
    if (mp4) probe.addAll(List.of("-enable_drefs", "0"));
    probe.addAll(
        List.of(
            "-i",
            source.toString(),
            "-show_entries",
            "stream=codec_type,codec_name,width,height:format=duration",
            "-of",
            "json"));
    JsonNode info = json.readTree(run(probe, 8));
    var videos = new ArrayList<JsonNode>();
    var audio = new ArrayList<JsonNode>();
    for (var stream : info.path("streams")) {
      if (stream.path("codec_type").asText().equals("video")) videos.add(stream);
      if (stream.path("codec_type").asText().equals("audio")) audio.add(stream);
    }
    if (videos.size() != 1 || audio.size() > 1)
      throw invalid("Use a single video track and at most one audio track");
    String codec = videos.getFirst().path("codec_name").asText();
    if (!(mp4 ? Set.of("h264") : Set.of("vp8", "vp9", "av1")).contains(codec))
      throw new AttachmentException(
          AttachmentException.Reason.UNSUPPORTED,
          "MP4 requires H.264; WebM requires VP8, VP9 or AV1");
    if (!audio.isEmpty()
        && !(mp4 ? Set.of("aac") : Set.of("opus", "vorbis"))
            .contains(audio.getFirst().path("codec_name").asText()))
      throw new AttachmentException(
          AttachmentException.Reason.UNSUPPORTED, "Unsupported audio codec");
    int width = videos.getFirst().path("width").asInt(),
        height = videos.getFirst().path("height").asInt();
    dimensions(width, height, 8_294_400);
    double duration = info.path("format").path("duration").asDouble(Double.NaN);
    if (Double.isFinite(duration) && (duration <= 0 || duration > 120))
      throw large("Video duration is limited to 120 seconds");
    var out = directory.resolve(mp4 ? "clean.mp4" : "clean.webm");
    var command =
        new ArrayList<>(
            List.of(
                ffmpeg,
                "-nostdin",
                "-max_alloc",
                "134217728",
                "-v",
                "error",
                "-xerror",
                "-max_alloc",
                "134217728",
                "-threads",
                "1",
                "-protocol_whitelist",
                "file,pipe",
                "-f",
                mp4 ? "mov" : "matroska"));
    if (mp4) command.addAll(List.of("-enable_drefs", "0"));
    command.addAll(
        List.of(
            "-i",
            source.toString(),
            "-map",
            "0:v:0",
            "-map",
            "0:a:0?",
            "-c",
            "copy",
            "-map_metadata",
            "-1",
            "-map_metadata:s",
            "-1",
            "-map_chapters",
            "-1"));
    if (mp4) command.addAll(List.of("-movflags", "+faststart"));
    command.addAll(List.of("-fs", "53477376", "-y", out.toString()));
    run(command, 25);
    var verifiedProbe =
        probe.stream()
            .map(value -> value.equals(source.toString()) ? out.toString() : value)
            .toList();
    double actualDuration =
        json.readTree(run(verifiedProbe, 8)).path("format").path("duration").asDouble(Double.NaN);
    if (!Double.isFinite(actualDuration) || actualDuration <= 0 || actualDuration > 120)
      throw large("Video duration is limited to 120 seconds");
    return new ProcessedAttachment(
        out,
        Attachment.Kind.VIDEO,
        mp4 ? "video/mp4" : "video/webm",
        width,
        height,
        actualDuration);
  }

  private byte[] run(List<String> command, int seconds) {
    Process process;
    try {
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
    } catch (IOException missing) {
      throw new AttachmentException(
          AttachmentException.Reason.UNAVAILABLE, "Media processing tools are unavailable");
    }
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var output =
          executor.submit(
              () -> {
                byte[] bytes = process.getInputStream().readNBytes(65537);
                if (bytes.length > 65536) process.destroyForcibly();
                return bytes;
              });
      try {
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          throw invalid("Media processing time limit exceeded");
        }
        byte[] bytes = output.get(2, TimeUnit.SECONDS);
        if (process.exitValue() != 0 || bytes.length > 65536)
          throw invalid("Media is malformed or unsupported");
        return bytes;
      } catch (InterruptedException interrupted) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
        throw new AttachmentException(
            AttachmentException.Reason.UNAVAILABLE, "Media processing interrupted");
      } catch (ExecutionException | TimeoutException failed) {
        process.destroyForcibly();
        throw invalid("Could not process media");
      }
    } finally {
      process.destroyForcibly();
    }
  }

  private static org.w3c.dom.Node child(org.w3c.dom.Node parent, String name) {
    for (var n = parent.getFirstChild(); n != null; n = n.getNextSibling())
      if (n.getNodeName().equals(name)) return n;
    return null;
  }

  private static void dimensions(int w, int h, long pixels) {
    if (w < 1 || h < 1 || w > 12000 || h > 12000 || (long) w * h > pixels)
      throw large("Media dimensions exceed the supported limits");
  }

  private static AttachmentException invalid(String message) {
    return new AttachmentException(AttachmentException.Reason.INVALID, message);
  }

  private static AttachmentException large(String message) {
    return new AttachmentException(AttachmentException.Reason.TOO_LARGE, message);
  }
}
