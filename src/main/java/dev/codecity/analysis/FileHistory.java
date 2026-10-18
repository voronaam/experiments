package dev.codecity.analysis;

/**
 * Git activity for one file, as mined by {@link GitHistory}. Every type declared in the file shares it.
 *
 * @param commits     number of non-merge commits that changed the file (renames are not followed)
 * @param authors     number of distinct author e-mail addresses (after {@code .mailmap}) among those commits
 * @param lastCommit  epoch seconds of the most recent commit touching the file
 * @param firstCommit epoch seconds of the oldest commit touching the file
 */
public record FileHistory(int commits, int authors, long lastCommit, long firstCommit) {
}
