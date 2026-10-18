/// Small helpers over tree-sitter nodes, shared by the Java, TypeScript and Rust analysers.
///
/// Tree-sitter is used through its C API (`codecity.c.treesitter`). A `TSNode` is a small value struct that points
/// into its `TSTree`; it stays valid only while the tree is alive, so analysers keep the tree until they have
/// extracted everything they need (`scope (exit) ts_tree_delete(tree)`) and must not keep nodes beyond that. Node
/// positions are byte offsets into the UTF-8 source (the default input encoding of `ts_parser_parse_string`), which
/// is why they can slice a D `string` directly; rows are 0-based.
module codecity.analysis.syntax;

import codecity.c.treesitter;
import core.stdc.string : strlen;

/// A parsed file: the tree, its source text, and helpers that slice node text out of it.
struct Src
{
    string code;

    /// The source text of a node. A slice of `code`, not a copy: it lives as long as the source string.
    string text(TSNode n) const nothrow @nogc
    {
        return code[ts_node_start_byte(n) .. ts_node_end_byte(n)];
    }

    /// The text of the child in grammar field `field`, or "" when the node has no such child.
    string fieldText(TSNode n, string field) const nothrow @nogc
    {
        auto c = child(n, field);
        return isNull(c) ? "" : text(c);
    }
}

/// The node's grammar kind, e.g. `class_declaration` or, for an anonymous token, its literal text such as `static`.
/// The C string is static data of the grammar, so slicing it without copying is safe.
string kind(TSNode n) nothrow @nogc
{
    auto p = ts_node_type(n);
    return cast(string) p[0 .. strlen(p)];
}

bool isNull(TSNode n) nothrow @nogc { return ts_node_is_null(n); }

/// Child by field name; check with `isNull`.
TSNode child(TSNode n, string field) nothrow @nogc
{
    return ts_node_child_by_field_name(n, field.ptr, cast(uint) field.length);
}

/// 1-based first and last source line of a node (tree-sitter rows are 0-based).
uint firstLine(TSNode n) nothrow @nogc { return ts_node_start_point(n).row + 1; }
uint lastLine(TSNode n) nothrow @nogc { return ts_node_end_point(n).row + 1; }

/// Named children as a range-friendly array (small: AST nodes have few children).
TSNode[] namedChildren(TSNode n) nothrow
{
    const count = ts_node_named_child_count(n);
    auto result = new TSNode[count];
    foreach (i; 0 .. count) result[i] = ts_node_named_child(n, i);
    return result;
}

/// All children, including anonymous keyword/operator tokens.
TSNode[] children(TSNode n) nothrow
{
    const count = ts_node_child_count(n);
    auto result = new TSNode[count];
    foreach (i; 0 .. count) result[i] = ts_node_child(n, i);
    return result;
}

/// The grammar field name of the `i`-th child (counting anonymous children too), or null when it has none.
string fieldNameForChild(TSNode n, uint i) nothrow @nogc
{
    auto p = ts_node_field_name_for_child(n, i);
    return p is null ? null : cast(string) p[0 .. strlen(p)];
}

/// The operator token of a binary expression, e.g. `&&`.
string operator(TSNode binary) nothrow @nogc
{
    auto op = child(binary, "operator");
    return isNull(op) ? "" : kind(op);
}

/// Depth-first pre-order walk over named nodes with a tree cursor; `visit` returns whether to descend.
///
/// `root` itself is visited first. Anonymous nodes (keywords, punctuation) are not passed to `visit` but are always
/// descended into, so named nodes below them are still found. The walk is iterative (a tree cursor, not recursion),
/// so its stack use does not grow with the nesting depth of the code.
///
/// The loop: go to the first child if allowed; otherwise climb until a next sibling exists. The walk ends when the
/// climb arrives back at `root`, so siblings of `root` are never visited.
void walk(TSNode root, scope bool delegate(TSNode) visit)
{
    auto cursor = ts_tree_cursor_new(root);
    scope (exit) ts_tree_cursor_delete(&cursor);
    bool descend = visit(root);
    while (true)
    {
        if (descend && ts_tree_cursor_goto_first_child(&cursor))
        {
            auto n = ts_tree_cursor_current_node(&cursor);
            descend = !ts_node_is_named(n) || visit(n);
            continue;
        }
        while (true)
        {
            if (ts_node_eq(ts_tree_cursor_current_node(&cursor), root)) return;
            if (ts_tree_cursor_goto_next_sibling(&cursor))
            {
                auto n = ts_tree_cursor_current_node(&cursor);
                descend = !ts_node_is_named(n) || visit(n);
                break;
            }
            if (!ts_tree_cursor_goto_parent(&cursor)) return;
        }
    }
}

/// Thread-local parsers (module-level variables are thread-local in D), one per grammar.
///
/// A `TSParser` must not be used by two threads at once, and the parsing workers in `SourceAnalyzer.analyze` run
/// concurrently, so each thread lazily creates its own. They are reused for every later file and never freed: the
/// worker threads of `taskPool` live as long as the process.
private TSParser*[string] parsers;

/// A grammar entry point such as `tree_sitter_java`.
alias LanguageFn = extern (C) const(TSLanguage)* function() nothrow @nogc;

/// Parses `code` with this thread's parser for `grammar` (the cache key, e.g. "java"), creating it on first use.
/// Returns null if tree-sitter gives up; the caller owns the tree and must `ts_tree_delete` it. Syntax errors do not
/// make it fail: they show up as ERROR/MISSING nodes, detectable with `ts_node_has_error`.
TSTree* parse(string grammar, LanguageFn language, string code)
{
    auto p = grammar in parsers;
    TSParser* parser;
    if (p is null)
    {
        parser = ts_parser_new();
        ts_parser_set_language(parser, language());
        parsers[grammar] = parser;
    }
    else parser = *p;
    return ts_parser_parse_string(parser, null, code.ptr, cast(uint) code.length);
}
