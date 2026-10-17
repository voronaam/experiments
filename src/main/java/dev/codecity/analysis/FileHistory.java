package dev.codecity.analysis;

/**
 * Git activity for one file.
 *
 * @param lastCommit  epoch seconds of the most recent commit touching the file
 * @param firstCommit epoch seconds of the oldest commit touching the file
 */
public record FileHistory(int commits, int authors, long lastCommit, long firstCommit) {
}
