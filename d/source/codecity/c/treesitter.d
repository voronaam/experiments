/// Bindings for the subset of the tree-sitter C API CodeCity uses (layouts mirror tree_sitter/api.h v0.27).
///
/// The library and the four grammars are compiled from pinned sources into `third_party/build/libcodecity_parsers.a`
/// by `scripts/build-native.sh` and linked statically. The analysers use these functions through the helpers in
/// `codecity.analysis.syntax`.
///
/// `TSNode` and `TSTreeCursor` are passed and returned by value, so their D layouts must match the C structs field
/// for field; the field contents are internal to tree-sitter and never touched here. When upgrading tree-sitter,
/// re-check them against `lib/include/tree_sitter/api.h`. All functions are `nothrow @nogc` C calls; ownership
/// follows the C API (a parser, tree or cursor must be freed with its `_delete` function).
module codecity.c.treesitter;

extern (C) @nogc nothrow:

// Opaque handles, only ever used through pointers.
struct TSLanguage;
struct TSParser;
struct TSTree;

/// A position in the source: zero-based row and byte column.
struct TSPoint
{
    uint row;
    uint column;
}

/// A syntax node: a lightweight value that refers into its tree and is only valid while the tree is alive.
struct TSNode
{
    uint[4] context;
    const(void)* id;
    const(TSTree)* tree;
}

struct TSTreeCursor
{
    const(void)* tree;
    const(void)* id;
    uint[3] context;
}

TSParser* ts_parser_new();
void ts_parser_delete(TSParser*);
bool ts_parser_set_language(TSParser*, const(TSLanguage)*);
/// Parses `length` bytes of UTF-8. `old` is a previous tree for incremental parsing (CodeCity passes null).
TSTree* ts_parser_parse_string(TSParser*, const(TSTree)* old, const(char)* source, uint length);
void ts_tree_delete(TSTree*);
TSNode ts_tree_root_node(const(TSTree)*);

/// The node's kind as named in the grammar, e.g. "class_declaration"; a static string owned by the language.
const(char)* ts_node_type(TSNode);
uint ts_node_start_byte(TSNode);
uint ts_node_end_byte(TSNode);
TSPoint ts_node_start_point(TSNode);
TSPoint ts_node_end_point(TSNode);
bool ts_node_is_null(TSNode);
bool ts_node_is_named(TSNode);
bool ts_node_has_error(TSNode);
uint ts_node_child_count(TSNode);
TSNode ts_node_child(TSNode, uint);
uint ts_node_named_child_count(TSNode);
TSNode ts_node_named_child(TSNode, uint);
TSNode ts_node_child_by_field_name(TSNode, const(char)* name, uint nameLength);
const(char)* ts_node_field_name_for_child(TSNode, uint);
bool ts_node_eq(TSNode, TSNode);

TSTreeCursor ts_tree_cursor_new(TSNode);
void ts_tree_cursor_delete(TSTreeCursor*);
bool ts_tree_cursor_goto_first_child(TSTreeCursor*);
bool ts_tree_cursor_goto_next_sibling(TSTreeCursor*);
bool ts_tree_cursor_goto_parent(TSTreeCursor*);
TSNode ts_tree_cursor_current_node(const(TSTreeCursor)*);

// The grammars, one entry point per language. TypeScript has two: plain `.ts` and `.tsx` (with JSX).
const(TSLanguage)* tree_sitter_java();
const(TSLanguage)* tree_sitter_typescript();
const(TSLanguage)* tree_sitter_tsx();
const(TSLanguage)* tree_sitter_rust();
