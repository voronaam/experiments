package dev.codecity.history;

/**
 * One sampled commit of the history replay: a frame of the city's growth.
 *
 * @param commit  full 40-hex sha
 * @param time    author time, epoch seconds ({@code %at})
 * @param author  author name as recorded in the commit ({@code %an}, no {@code .mailmap})
 * @param subject first line of the commit message ({@code %s})
 * @param types   number of types the analysis found in the tree at this commit
 */
public record Snapshot(String commit, long time, String author, String subject, int types) {
}
