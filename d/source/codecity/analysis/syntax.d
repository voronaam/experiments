/// Small helpers over tree-sitter nodes.
module codecity.analysis.syntax;

import codecity.c.treesitter;
import core.stdc.string : strlen;

/// A parsed file: the tree, its source text, and helpers that slice node text out of it.
struct Src
{
    string code;

    string text(TSNode n) const nothrow @nogc
    {
        return code[ts_node_start_byte(n) .. ts_node_end_byte(n)];
    }

    string fieldText(TSNode n, string field) const nothrow @nogc
    {
        auto c = child(n, field);
        return isNull(c) ? "" : text(c);
    }
}

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
private TSParser*[string] parsers;

/// A grammar entry point such as `tree_sitter_java`.
alias LanguageFn = extern (C) const(TSLanguage)* function() nothrow @nogc;

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
