package dev.codecity.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The walk over a hostile tree (the Rust and D editions have the same test): links to files and directories outside
 * the tree, a link cycle, a FIFO, a name that is not UTF-8 and an oversized file. Only the two ordinary files count;
 * the oversized one is found but fails, and nothing blocks or escapes the tree.
 */
class SourceWalkTest {

    @Test
    void walkStaysInsideTheTreeAndSkipsSpecialFiles(@TempDir Path tmp) throws Exception {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "needs symlinks, mkfifo and byte file names");
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("Secret.java"), "class Secret {}");
        Path root = Files.createDirectories(tmp.resolve("root/src"));
        Files.writeString(root.resolve("A.java"), "class A {}");
        Files.writeString(root.resolve("B.ts"), "export class B {}");
        Files.createSymbolicLink(root.resolve("Link.java"), outside.resolve("Secret.java"));
        Files.createSymbolicLink(root.resolve("outlink"), outside);
        Files.createSymbolicLink(root.resolve("loop"), root);
        try (RandomAccessFile big = new RandomAccessFile(root.resolve("Big.java").toFile(), "rw")) {
            big.setLength(SourceAnalyzer.MAX_FILE_BYTES + 1); // sparse: nothing is written
        }
        Process p = new ProcessBuilder("sh", "-c", "mkfifo Fifo.java && printf 'class Bad {}' > \"$(printf 'B\\377d.java')\"")
                .directory(root.toFile()).inheritIO().start();
        assertEquals(0, p.waitFor());

        Analysis a = new SourceAnalyzer().analyze(tmp.resolve("root"), true);
        assertEquals(List.of("A", "src/B.ts:B"), a.types().stream().map(TypeInfo::fqn).toList());
        assertEquals(3, a.files()); // A.java, B.ts and Big.java
        assertEquals(1, a.failedFiles()); // Big.java is too large to parse
    }
}
