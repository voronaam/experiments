//! Small helpers over tree-sitter nodes.

use tree_sitter::Node;

pub struct Src<'a> {
    pub code: &'a str,
}

impl<'a> Src<'a> {
    pub fn text(&self, n: Node) -> &'a str {
        &self.code[n.byte_range()]
    }

    pub fn field_text(&self, n: Node, field: &str) -> &'a str {
        n.child_by_field_name(field).map(|c| self.text(c)).unwrap_or("")
    }
}

pub fn first_line(n: Node) -> u32 {
    n.start_position().row as u32 + 1
}

pub fn last_line(n: Node) -> u32 {
    n.end_position().row as u32 + 1
}

pub fn named_children<'t>(n: Node<'t>) -> impl Iterator<Item = Node<'t>> {
    (0..n.named_child_count()).filter_map(move |i| n.named_child(i as u32))
}

pub fn children<'t>(n: Node<'t>) -> impl Iterator<Item = Node<'t>> {
    (0..n.child_count()).filter_map(move |i| n.child(i as u32))
}

/// Depth-first pre-order walk over named nodes (iterative, with a tree cursor: no recursion, no allocation per node).
/// `visit` returns whether to descend into the node's children.
pub fn walk<'t>(root: Node<'t>, mut visit: impl FnMut(Node<'t>) -> bool) {
    let mut cursor = root.walk();
    let mut descend = visit(root);
    loop {
        if descend && cursor.goto_first_child() {
            let n = cursor.node();
            descend = !n.is_named() || visit(n);
            continue;
        }
        loop {
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

/// The operator token of a binary expression, e.g. `&&`.
pub fn operator<'t>(binary: Node<'t>) -> &'t str {
    binary.child_by_field_name("operator").map(|o| o.kind()).unwrap_or("")
}
