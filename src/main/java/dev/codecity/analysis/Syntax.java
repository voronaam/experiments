package dev.codecity.analysis;

import org.treesitter.TSNode;
import org.treesitter.TSParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** A tree-sitter syntax tree plus the source bytes it indexes into, with small navigation helpers. */
final class Syntax {

    final TSNode root;
    private final byte[] utf8;

    private Syntax(TSNode root, byte[] utf8) {
        this.root = root;
        this.utf8 = utf8;
    }

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

    static int firstLine(TSNode n) {
        return n.getStartPoint().getRow() + 1;
    }

    static int lastLine(TSNode n) {
        return n.getEndPoint().getRow() + 1;
    }

    static TSNode field(TSNode n, String name) {
        TSNode c = n.getChildByFieldName(name);
        return c == null || c.isNull() ? null : c;
    }

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

    /** Depth-first pre-order walk over named nodes; {@code descend} decides whether to enter a node's children. */
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

    /** The operator token of a binary expression, e.g. {@code &&}. */
    static String operator(TSNode binary) {
        TSNode op = field(binary, "operator");
        return op == null ? "" : op.getType();
    }
}
