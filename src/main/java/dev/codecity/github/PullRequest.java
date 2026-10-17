package dev.codecity.github;

import java.util.List;

/** An open pull request and the repository-relative paths of the files it changes. */
public record PullRequest(int number, String title, String url, String author, String updatedAt, boolean draft,
                          List<String> files) {
}
