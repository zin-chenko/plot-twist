package io.plottwist.domain.attachment.model;

/** Single byte ranges, including suffix/open-ended ranges, for native browser video seeking. */
public record ByteRange(long start, long end, boolean partial) {
  public long length() {
    return end - start + 1;
  }

  public static ByteRange parse(String header, long size) {
    if (size <= 0) throw new IllegalArgumentException("Empty media");
    if (header == null) return new ByteRange(0, size - 1, false);
    if (header.length() > 80 || !header.matches("bytes=[0-9]*-[0-9]*"))
      throw new IllegalArgumentException("Only one byte range is supported");
    var parts = header.substring(6).split("-", -1);
    try {
      if (parts[0].isEmpty()) {
        long suffix = Long.parseLong(parts[1]);
        if (suffix <= 0) throw new IllegalArgumentException();
        return new ByteRange(Math.max(0, size - suffix), size - 1, true);
      }
      long start = Long.parseLong(parts[0]),
          end = parts[1].isEmpty() ? size - 1 : Math.min(Long.parseLong(parts[1]), size - 1);
      if (start >= size || end < start) throw new IllegalArgumentException();
      return new ByteRange(start, end, true);
    } catch (NumberFormatException bad) {
      throw new IllegalArgumentException("Invalid byte range");
    }
  }
}
