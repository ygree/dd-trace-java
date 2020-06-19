package datadog.trace.agent.tooling.bytebuddy.matcher

import datadog.trace.util.test.DDSpecification

class TriePackagePrefixMatcherTest extends DDSpecification {

  def "test matcher with #prefixes"() {
    setup:
    def matcher = new TriePackagePrefixMatcher(prefixes as String[])

    expect:
    matcher.matches(input) == expected

    where:
    prefixes       | input   | expected
    []             | "a.b.C" | false
    [""]           | "a.b.C" | false
    ["a.b"]        | "a.b.C" | true
    ["a.b."]       | "a.b.C" | true
    ["a.b.c"]      | "a.b.C" | false
    ["x"]          | "a.b.C" | false
    ["a.x"]        | "a.b.C" | false
    ["a.C"]        | "a.b.C" | false
    ["a.x", "a"]   | "a.b.C" | true
    ["a.x", "a."]  | "a.b.C" | true
    ["a", "a.x"]   | "a.b.C" | true
    ["a.", "a.x"]  | "a.b.C" | true
    ["a.x", "a.b"] | "a.b.C" | true
    ["a.b", "a.b"] | "a.b.C" | true
    ["a"]          | "a"     | true
    ["a.b"]        | "a"     | false
    ["a"]          | ""      | false
  }
}
