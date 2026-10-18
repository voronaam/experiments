//! Small helpers over tree-sitter nodes.
//!
//! Every language analyser works on a tree-sitter concrete syntax tree. Nodes only carry byte ranges and positions,
//! not text, so [`Src`] pairs the source string with the tree to slice text out of it. tree-sitter distinguishes
//! *named* nodes (grammar rules such as `class_declaration`, `identifier`) from anonymous ones (punctuation and
//! keywords such as `{`, `static`, `&&`); most analysis only looks at named nodes.

use tree_sitter::Node;

/// The source text a tree was parsed from. Byte ranges of nodes index into `code`.
pub struct Src<'a> {
    pub code: &'a str,
}

impl<'a> Src<'a> {
    /// The node's source text. tree-sitter byte ranges always fall on UTF-8 boundaries, so slicing cannot panic.
    pub fn text(&self, n: Node) -> &'a str {
        &self.code[n.byte_range()]
    }

    /// Text of the child stored under grammar field `field` (e.g. `name`, `body`), or "" if the node has none.
    pub fn field_text(&self, n: Node, field: &str) -> &'a str {
        n.child_by_field_name(field).map(|c| self.text(c)).unwrap_or("")
    }
}

/// 1-based line the node starts on (tree-sitter rows are 0-based; editors and the viewers count from 1).
pub fn first_line(n: Node) -> u32 {
    n.start_position().row as u32 + 1
}

/// 1-based line the node ends on (inclusive).
pub fn last_line(n: Node) -> u32 {
    n.end_position().row as u32 + 1
}

/// Direct named children, skipping punctuation and keyword tokens. Comments are named nodes too and do appear.
pub fn named_children<'t>(n: Node<'t>) -> impl Iterator<Item = Node<'t>> {
    (0..n.named_child_count()).filter_map(move |i| n.named_child(i as u32))
}

/// All direct children, including anonymous tokens such as `static`, `default` or `abstract`.
pub fn children<'t>(n: Node<'t>) -> impl Iterator<Item = Node<'t>> {
    (0..n.child_count()).filter_map(move |i| n.child(i as u32))
}

/// Depth-first pre-order walk over named nodes (iterative, with a tree cursor: no recursion, no allocation per node).
/// `visit` returns whether to descend into the node's children.
///
/// `root` itself is visited too. Anonymous nodes are not passed to `visit`, but the walk still descends into them
/// (`descend` is forced to true), so named nodes below an anonymous one are not missed. The walk never leaves
/// `root`: when climbing back up reaches it, the walk is over.
pub fn walk<'t>(root: Node<'t>, mut visit: impl FnMut(Node<'t>) -> bool) {
    let mut cursor = root.walk();
    let mut descend = visit(root);
    loop {
        // Go down first (pre-order) if the current node wants its children visited and has any.
        if descend && cursor.goto_first_child() {
            let n = cursor.node();
            descend = !n.is_named() || visit(n);
            continue;
        }
        // Otherwise move to the next sibling, climbing up until one exists.
        loop {
            // Back at the root: siblings of the root are outside the subtree, so stop.
            if cursor.node() == root {
                return;
            }
            if cursor.goto_next_sibling() {
                let n = cursor.node();
                descend = !n.is_named() || visit(n);
                break;
            }
            if !cursor.goto_parent() {
                return;
            }
        }
    }
}

/// The operator token of a binary expression, e.g. `&&`. The `operator` field holds an anonymous token whose kind
/// is the operator itself, so no text lookup is needed. "" if the node has no such field.
pub fn operator<'t>(binary: Node<'t>) -> &'t str {
    binary.child_by_field_name("operator").map(|o| o.kind()).unwrap_or("")
}
