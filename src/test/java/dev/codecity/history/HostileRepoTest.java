package dev.codecity.history;

import dev.codecity.CityModel;
import dev.codecity.analysis.GitHistory;
import dev.codecity.analysis.SourceAnalyzer;
import dev.codecity.github.GitHubInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CodeCity's git commands on a hostile repository (built by {@code src/test/resources/hostile-repo.sh}, shared with
 * the Rust and D editions): its configuration names programs for git to run, and its trees hold paths that escape the
 * work tree. Neither may have any effect.
 */
class HostileRepoTest {

    /** Builds the hostile repository in {@code dir/repo}, with its marker programs in {@code dir/m}. */
    private static Path hostile(Path dir) throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        Path markers = Files.createDirectories(dir.resolve("m"));
        Path script = Path.of(HostileRepoTest.class.getResource("/hostile-repo.sh").toURI());
        Process p = new ProcessBuilder("sh", script.toString(), markers.toString()).directory(repo.toFile())
                .inheritIO().start();
        assertEquals(0, p.waitFor());
        return repo.toRealPath();
    }

    /** The "hit-*" files of the marker programs that ran. */
    private static List<String> hits(Path dir) throws Exception {
        try (Stream<Path> files = Files.list(dir.resolve("m"))) {
            return files.map(f -> f.getFileName().toString()).filter(n -> n.startsWith("hit-")).sorted().toList();
        }
    }

    /** Runs plain git (as a user would) in {@code repo}, with {@code input} on stdin. */
    private static void plainGit(Path repo, String input, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (var in = p.getOutputStream()) {
            in.write(input.getBytes());
        }
        p.waitFor();
    }

    @Test
    void theFixtureIsHostileToPlainGit(@TempDir Path dir) throws Exception {
        Path repo = hostile(dir);
        // Without the overrides, git log runs gpg.program and reading the missing blob runs the remote's upload-pack.
        plainGit(repo, "", "log", "-1", "--format=%H");
        plainGit(repo, "2583475c12b210f9cbbaf47bb33faada288252fc\n", "cat-file", "--batch");
        assertEquals(List.of("hit-gpg", "hit-uploadpack"), hits(dir));
    }

    @Test
    void runsNoProgramAndWritesNothingOutside(@TempDir Path dir) throws Exception {
        Path repo = hostile(dir);
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Path escA = tmp.resolve("EscA.java");
        Path escB = dir.resolve("m").resolve("EscB.java");
        Files.deleteIfExists(escA);

        assertTrue(GitHubInfo.detect(repo).isPresent());
        assertFalse(GitHistory.load(repo).isEmpty());
        CityModel model = CityModel.build(new SourceAnalyzer(), repo, true, true, null, 60);

        assertEquals(List.of(), hits(dir));
        assertFalse(Files.exists(escA));
        assertFalse(Files.exists(escB));
        assertFalse(Files.exists(tmp.resolve("EscC.java")));
        // Every snapshot holds just A: the escaping entries are skipped, and Z's blob is missing (not fetched).
        assertTrue(model.hasHistory());
        assertEquals(List.of(1, 1, 1), model.history().stream().map(Snapshot::types).toList());
    }

    @Test
    void onlyPlainRelativePathsAreSafe() {
        assertTrue(CityHistory.safePath("src/A.java"));
        assertTrue(CityHistory.safePath("a.b/..c/.gitx/A.java"));
        for (String bad : List.of("../A.java", "a/../../A.java", "/tmp/A.java", "a//A.java", "./A.java", "a/",
                ".git/A.java", "a/.GIT/A.java", "a\\..\\A.java", "C:A.java", "")) {
            assertFalse(CityHistory.safePath(bad), bad);
        }
    }
}
