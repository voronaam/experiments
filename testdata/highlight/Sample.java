package com.acme;

import java.util.List;

/**
 * A sample: "quotes" inside a comment stay comment.
 */
@Deprecated(since = "1")
public final class Sample<T> implements Comparable<Sample<T>> {
    static final int MAX_SIZE = 0x1F_FF;
    private final String name = "a \"b\" c";
    private char c = '\'';
    String block = """
        text "block" here
        """;

    @Override
    public int compareTo(Sample<T> o) { // trailing comment
        return name.length() > 3.5e2 ? -1 : 1L == 2 ? 0 : 1;
    }
}
