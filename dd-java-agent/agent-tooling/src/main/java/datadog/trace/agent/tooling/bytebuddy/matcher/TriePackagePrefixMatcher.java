package datadog.trace.agent.tooling.bytebuddy.matcher;

import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.matcher.ElementMatcher;

public class TriePackagePrefixMatcher extends ElementMatcher.Junction.AbstractBase<String> {
  private final TriePackageSegmentsPrefixMatcher matcher;

  public TriePackagePrefixMatcher(final String... packagePrefixes) {
    matcher = new TriePackageSegmentsPrefixMatcher(packagePrefixes);
  }

  @Override
  public boolean matches(final String target) {
    return matcher.matches(target.split("\\."), 0);
  }

  @Override
  public String toString() {
    return matcher.toString();
  }

  static class TriePackageSegmentsPrefixMatcher {
    // null map means a terminal search.
    private final Map<String, TriePackageSegmentsPrefixMatcher> map;

    TriePackageSegmentsPrefixMatcher(final String... packagePrefixes) {
      map = new HashMap<>();
      for (final String prefix : packagePrefixes) {
        add(prefix.split("\\."), 0);
      }
    }

    private TriePackageSegmentsPrefixMatcher(final String[] segments, final int i) {
      if (segments.length <= i) {
        map = null;
      } else {
        map = new HashMap<>();
        map.put(segments[i], new TriePackageSegmentsPrefixMatcher(segments, i + 1));
      }
    }

    private void add(final String[] segments, final int i) {
      assert i < segments.length;
      if (map == null) {
        // already terminal.
        return;
      }
      if (!map.containsKey(segments[i]) || segments.length == i + 1) {
        map.put(segments[i], new TriePackageSegmentsPrefixMatcher(segments, i + 1));
      } else {
        map.get(segments[i]).add(segments, i + 1);
      }
    }

    public boolean matches(final String[] segments, final int i) {
      if (map == null) {
        return true;
      }
      if (segments.length <= i) {
        return false;
      }
      if (map.containsKey(segments[i])) {
        return map.get(segments[i]).matches(segments, i + 1);
      }
      return false;
    }

    @Override
    public String toString() {
      if (map == null) {
        return "*";
      }
      final StringBuilder sb = new StringBuilder("{");
      for (final Map.Entry<String, TriePackageSegmentsPrefixMatcher> entry : map.entrySet()) {
        sb.append(entry.getKey());
        sb.append("->");
        sb.append(entry.getValue());
      }
      sb.append("}");
      return sb.toString();
    }
  }
}
