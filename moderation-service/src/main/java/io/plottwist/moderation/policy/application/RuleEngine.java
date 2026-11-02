package io.plottwist.moderation.policy.application;

import io.plottwist.moderation.contract.*;
import io.plottwist.moderation.contract.ModerationDecision.Outcome;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Token-based Aho-Corasick matching. No unbounded user-supplied regex is executed. */
public final class RuleEngine {
  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
  private final List<Node> nodes = new ArrayList<>();
  private final String rulesHash;

  private static class Node {
    final Map<String, Integer> edges = new HashMap<>();
    int fail;
    final List<Rule> matches = new ArrayList<>();
  }

  private record Rule(String id, Outcome outcome) {}

  public RuleEngine(byte[] rules) {
    try {
      rulesHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rules));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    nodes.add(new Node());
    var identifiers = new HashSet<String>();
    var lines = new String(rules, StandardCharsets.UTF_8).lines().toList();
    if (lines.size() > 10000) throw new IllegalArgumentException("Too many moderation rules");
    for (var line : lines) {
      if (line.isBlank() || line.startsWith("#")) continue;
      var fields = line.split("\t", -1);
      if (fields.length != 3
          || !fields[1].matches("[a-z0-9-]{1,64}")
          || !identifiers.add(fields[1])
          || fields[2].length() > 500)
        throw new IllegalArgumentException("Invalid moderation rule");
      var outcome = Outcome.valueOf(fields[0]);
      if (outcome == Outcome.APPROVE)
        throw new IllegalArgumentException("Rule must block or review");
      var words = words(fields[2]);
      if (words.isEmpty()) throw new IllegalArgumentException("Empty rule");
      int node = 0;
      for (var word : words) {
        var existing = nodes.get(node).edges.get(word);
        if (existing == null) {
          existing = nodes.size();
          nodes.get(node).edges.put(word, existing);
          nodes.add(new Node());
        }
        node = existing;
      }
      nodes.get(node).matches.add(new Rule(fields[1], outcome));
    }
    var queue = new ArrayDeque<Integer>();
    queue.addAll(nodes.getFirst().edges.values());
    while (!queue.isEmpty()) {
      int parent = queue.remove();
      for (var edge : nodes.get(parent).edges.entrySet()) {
        int child = edge.getValue(), fail = nodes.get(parent).fail;
        while (fail != 0 && !nodes.get(fail).edges.containsKey(edge.getKey()))
          fail = nodes.get(fail).fail;
        nodes.get(child).fail = nodes.get(fail).edges.getOrDefault(edge.getKey(), 0);
        nodes.get(child).matches.addAll(nodes.get(nodes.get(child).fail).matches);
        queue.add(child);
      }
    }
  }

  public ModerationDecision evaluate(ModerationTask task) {
    if (!BrokerNames.POLICY.equals(task.policyVersion()))
      throw new IllegalArgumentException("Unsupported moderation policy");
    var matched = new LinkedHashMap<String, Outcome>();
    scan(words(task.title()), matched);
    scan(words(task.body()), matched);
    var outcome =
        matched.containsValue(Outcome.BLOCK)
            ? Outcome.BLOCK
            : matched.isEmpty() ? Outcome.APPROVE : Outcome.REVIEW;
    return new ModerationDecision(
        1,
        task.jobId(),
        task.storyId(),
        task.revision(),
        task.contentHash(),
        task.policyVersion(),
        rulesHash,
        outcome,
        matched.keySet().stream().limit(10).toList());
  }

  private void scan(List<String> words, Map<String, Outcome> matches) {
    int node = 0;
    for (var word : words) {
      while (node != 0 && !nodes.get(node).edges.containsKey(word)) node = nodes.get(node).fail;
      node = nodes.get(node).edges.getOrDefault(word, 0);
      for (var match : nodes.get(node).matches) matches.put(match.id(), match.outcome());
    }
  }

  static List<String> words(String input) {
    var text = new StringBuilder();
    Normalizer.normalize(input, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .codePoints()
        .filter(c -> Character.getType(c) != Character.FORMAT)
        .forEach(
            c ->
                text.appendCodePoint(
                    switch (c) {
                      case 'ё' -> 'е';
                      case 'a' -> 'а';
                      case 'c' -> 'с';
                      case 'e' -> 'е';
                      case 'o' -> 'о';
                      case 'p' -> 'р';
                      case 'x' -> 'х';
                      case 'y' -> 'у';
                      case 'k' -> 'к';
                      default -> c;
                    }));
    var matcher = WORD.matcher(text);
    var tokens = new ArrayList<String>();
    while (matcher.find()) tokens.add(matcher.group().replaceAll("(.)\\1{2,}", "$1"));
    // Join letter-by-letter masking such as х*у*й; ordinary words stay separate.
    var result = new ArrayList<String>();
    for (int i = 0; i < tokens.size(); ) {
      if (tokens.get(i).length() == 1) {
        int end = i;
        var joined = new StringBuilder();
        while (end < tokens.size() && tokens.get(end).length() == 1)
          joined.append(tokens.get(end++));
        if (end - i >= 3) {
          result.add(joined.toString());
          i = end;
          continue;
        }
      }
      result.add(tokens.get(i++));
    }
    return result;
  }
}
