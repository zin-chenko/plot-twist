package io.plottwist.moderation.contract;

import java.util.*;
import java.util.regex.Pattern;

/** The only embedded media syntax accepted by the application; never arbitrary HTML/URLs. */
public final class MediaMarkup {
  private MediaMarkup() {}

  private static final Pattern MARKER =
      Pattern.compile(
          "!\\[[^\\]\\n]{0,120}\\]\\(attachment:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\)");

  public static List<UUID> ids(String body) {
    var matcher = MARKER.matcher(body);
    var ids = new LinkedHashSet<UUID>();
    int count = 0;
    while (matcher.find()) {
      if (++count > 8) throw new IllegalArgumentException("Maximum 8 media insertions per story");
      ids.add(UUID.fromString(matcher.group(1)));
    }
    return List.copyOf(ids);
  }

  public static String textForEmbedding(String body) {
    return MARKER
        .matcher(body)
        .replaceAll(
            match ->
                java.util.regex.Matcher.quoteReplacement(
                    match.group().substring(2, match.group().indexOf(']'))));
  }
}
