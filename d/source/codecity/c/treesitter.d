/// Bindings for the subset of the tree-sitter C API CodeCity uses (layouts mirror tree_sitter/api.h v0.27).
module codecity.c.treesitter;

extern (C) @nogc nothrow:

struct TSLanguage;
struct TSParser;
struct TSTree;

struct TSPoint
{
    uint row;
    uint column;
}

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
TSTree* ts_parser_parse_string(TSParser*, const(TSTree)* old, const(char)* source, uint length);
void ts_tree_delete(TSTree*);
TSNode ts_tree_root_node(const(TSTree)*);

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

const(TSLanguage)* tree_sitter_java();
const(TSLanguage)* tree_sitter_typescript();
const(TSLanguage)* tree_sitter_tsx();
const(TSLanguage)* tree_sitter_rust();
