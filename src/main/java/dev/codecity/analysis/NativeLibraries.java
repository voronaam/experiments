package dev.codecity.analysis;

import dev.codecity.github.RepoCloner;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Points the tree-sitter binding at CodeCity's own build of the tree-sitter core library.
 *
 * <p>The library in the binding's jar (io.github.bonede:tree-sitter) is compiled without optimisation, and its JNI
 * glue looks up a class and six field ids for every node it returns, which made parsing several times slower than in
 * the Rust and D editions. {@code native/build-tree-sitter.sh} builds the same glue, with those lookups cached, and the
 * tree-sitter runtime with {@code -O2} for every platform the binding supports, into
 * {@code native/tree-sitter/lib/<arch>-<os>-tree-sitter.<ext>} on the classpath.
 *
 * <p>The binding loads its natives from the directory named by the system property {@code tree-sitter-lib} (as
 * {@code <dir>/lib/<file>}) when the file exists there, else from its own jar. {@link #install} sets that property
 * before the first parser is created: to the classpath directory itself when running from {@code target/classes},
 * otherwise to a copy extracted from the jar into {@code ~/.cache/codecity/native/<hash>/} (honouring
 * {@code XDG_CACHE_HOME}). The directory name is a hash of the library, so a new build never overwrites a library a
 * running process has loaded, and later runs reuse the copy. The grammars are not rebuilt: their files are not in that
 * directory, so the binding keeps taking them from the grammar jars.
 *
 * <p>Everything here is best effort. On a platform without a build, if extraction fails, or when
 * {@code -Dtree-sitter-lib} is given on the command line, the binding behaves as it would without CodeCity's library.
 */
final class NativeLibraries {

    /** The system property read by the binding's {@code org.treesitter.utils.NativeUtils}. */
    private static final String PROPERTY = "tree-sitter-lib";
    /** Classpath directory holding the {@code lib/} directory of the builds. */
    private static final String RESOURCE_DIR = "native/tree-sitter/";

    private static boolean installed;

    private NativeLibraries() {
    }

    /** Sets {@code tree-sitter-lib} if a build for this platform exists. Idempotent and thread-safe. */
    static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        if (System.getProperty(PROPERTY) != null) {
            return; // chosen by the user
        }
        String file = fileName();
        if (file == null) {
            return;
        }
        URL url = NativeLibraries.class.getClassLoader().getResource(RESOURCE_DIR + "lib/" + file);
        if (url == null) {
            return;
        }
        try {
            Path dir = url.getProtocol().equals("file")
                    ? Path.of(url.toURI()).getParent().getParent()
                    : extract(url, file);
            System.setProperty(PROPERTY, dir.toString());
        } catch (IOException | URISyntaxException | RuntimeException e) {
            System.err.println("warning: using the unoptimised tree-sitter library (" + e.getMessage() + ")");
        }
    }

    /**
     * The library's file name as the binding spells it ({@code NativeUtils.getFullLibName}), e.g.
     * {@code x86_64-linux-gnu-tree-sitter.so}, or null for a platform the binding does not support either.
     */
    private static String fileName() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String platform;
        String ext;
        if (os.contains("windows")) {
            platform = "windows";
            ext = "dll";
        } else if (os.contains("linux")) {
            platform = "linux-gnu";
            ext = "so";
        } else if (os.contains("mac")) {
            platform = "macos";
            ext = "dylib";
        } else {
            return null;
        }
        if (arch.contains("amd64") || arch.contains("x86_64")) {
            arch = "x86_64";
        } else if (arch.contains("aarch64")) {
            arch = "aarch64";
        } else {
            return null;
        }
        return arch + "-" + platform + "-tree-sitter." + ext;
    }

    /**
     * Copies the library out of the jar into {@code <cache>/codecity/native/<hash>/lib/<file>} unless it is already
     * there, and returns {@code <cache>/codecity/native/<hash>}. The copy is written to a temporary file and moved into
     * place, so a concurrently starting process never loads a half-written library.
     */
    private static Path extract(URL url, String file) throws IOException {
        byte[] bytes;
        try (InputStream in = url.openStream()) {
            bytes = in.readAllBytes();
        }
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JDK has SHA-256
        }
        Path dir = RepoCloner.cacheRoot().resolveSibling("native").resolve(hash);
        Path target = dir.resolve("lib").resolve(file);
        if (Files.isRegularFile(target) && Files.size(target) == bytes.length) {
            return dir;
        }
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), file, ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return dir;
    }
}
