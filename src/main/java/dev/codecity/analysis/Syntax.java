package dev.codecity.analysis;

import org.treesitter.TSNode;
import org.treesitter.TSParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A tree-sitter syntax tree plus the source bytes it indexes into, with small navigation helpers. Used by
 * {@link TypeScriptAnalyzer} and {@link RustAnalyzer}.
 *
 * <p>Tree-sitter never gives up on a file: syntax errors become {@code ERROR}/{@code MISSING} nodes inside an
 * otherwise normal tree, which {@code root.hasError()} reports. The analysers therefore always get a tree and mark the
 * file as failed only for the statistics.
 *
 * <p>The helpers normalise the binding's "null node" convention: tree-sitter returns a node whose {@code isNull()} is
 * true rather than {@code null} for an absent field, and {@link #field} turns that into a real {@code null}.
 *
 * <p>An instance lives only for one {@code parse} call of an analyser. The analysers keep one {@link TSParser} per
 * thread (in a {@code ThreadLocal}) because files are parsed in parallel and a parser must not be shared between
 * threads.
 */
final class Syntax {

    final TSNode root;
    /** The source as UTF-8, kept because node positions are byte offsets into exactly this encoding. */
    private final byte[] utf8;

    private Syntax(TSNode root, byte[] utf8) {
        this.root = root;
        this.utf8 = utf8;
    }

    /** Parses {@code code} from scratch (no previous tree, so no incremental re-parse). */
    static Syntax parse(TSParser parser, String code) {
        return new Syntax(parser.parseString(null, code).getRootNode(), code.getBytes(StandardCharsets.UTF_8));
    }

    /** Source text of a node (tree-sitter offsets are UTF-8 byte offsets). */
    String text(TSNode n) {
        if (n == null || n.isNull()) {
            return "";
        }
        return new String(utf8, n.getStartByte(), n.getEndByte() - n.getStartByte(), StandardCharsets.UTF_8);
    }

    /** 1-based line of the node's first character (tree-sitter rows are 0-based). */
    static int firstLine(TSNode n) {
        return n.getStartPoint().getRow() + 1;
    }

    /** 1-based line of the node's end point. */
    static int lastLine(TSNode n) {
        return n.getEndPoint().getRow() + 1;
    }

    /** The child stored under a grammar field name such as {@code "name"} or {@code "body"}, or null if absent. */
    static TSNode field(TSNode n, String name) {
        TSNode c = n.getChildByFieldName(name);
        return c == null || c.isNull() ? null : c;
    }

    /** Children that are grammar rules (declarations, expressions, identifiers), without punctuation and keywords. */
    static List<TSNode> namedChildren(TSNode n) {
        List<TSNode> list = new ArrayList<>(n.getNamedChildCount());
        for (int i = 0; i < n.getNamedChildCount(); i++) {
            list.add(n.getNamedChild(i));
        }
        return list;
    }

    /** Anonymous (keyword/operator) children included. */
    static List<TSNode> children(TSNode n) {
        List<TSNode> list = new ArrayList<>(n.getChildCount());
        for (int i = 0; i < n.getChildCount(); i++) {
            list.add(n.getChild(i));
        }
        return list;
    }

    /**
     * Depth-first pre-order walk over named nodes; {@code descend} decides whether to enter a node's children.
     * {@code n} itself is always visited. Recursive: the recursion is as deep as the syntax tree.
     */
    static void walk(TSNode n, Consumer<TSNode> visit, Predicate<TSNode> descend) {
        visit.accept(n);
        if (!descend.test(n)) {
            return;
        }
        for (int i = 0; i < n.getNamedChildCount(); i++) {
            walk(n.getNamedChild(i), visit, descend);
        }
    }

    static void walk(TSNode n, Consumer<TSNode> visit) {
        walk(n, visit, x -> true);
    }

    /**
     * The operator token of a binary expression, e.g. {@code &&}. The operator is an anonymous node, and for anonymous
     * nodes the node type is the token text itself.
     */
    static String operator(TSNode binary) {
        TSNode op = field(binary, "operator");
        return op == null ? "" : op.getType();
    }
}
