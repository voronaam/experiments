//! Lays the city out: packages become nested, stacked districts and types become buildings whose footprint grows
//! with their number of attributes. Each district's contents are packed with a skyline bin-packer, trying several
//! strip widths and keeping the most compact, square-ish result. (A faithful port of the Java version.)

use crate::model::{Language, TypeInfo};
use std::collections::{BTreeMap, HashMap};

/// Thickness of one district platform.
pub const PLATFORM: f64 = 0.4;
/// Street width between neighbouring lots.
pub const GAP: f64 = 0.8;
/// Kerb between a district's edge and its contents.
pub const PAD: f64 = 1.0;

#[derive(Clone, Debug)]
pub struct District {
    pub id: String,
    pub label: String,
    pub depth: u32,
    pub x: f64,
    pub z: f64,
    pub w: f64,
    pub d: f64,
    pub types: u32,
}

/// Where a building stands: footprint centre, side length and the height of the ground it stands on.
#[derive(Clone, Copy, Debug)]
pub struct Lot {
    pub x: f64,
    pub z: f64,
    pub side: f64,
    pub y: f64,
    pub district: usize,
}

pub struct Layout {
    pub districts: Vec<District>,
    pub lots: HashMap<String, Lot>,
    pub width: f64,
    pub depth: f64,
}

pub fn side(t: &TypeInfo) -> f64 {
    1.2 + 0.8 * (t.noa as f64).sqrt()
}

struct Node<'a> {
    label: String,
    id: String,
    children: BTreeMap<String, Node<'a>>,
    types: Vec<&'a TypeInfo>,
    module: bool,
    separator: &'static str,
    w: f64,
    d: f64,
    items: Vec<Item>,
}

impl<'a> Node<'a> {
    fn new(label: String, id: String) -> Self {
        Node { label, id, children: BTreeMap::new(), types: vec![], module: false, separator: ".", w: 0.0, d: 0.0, items: vec![] }
    }

    fn type_count(&self) -> u32 {
        self.types.len() as u32 + self.children.values().map(Node::type_count).sum::<u32>()
    }
}

/// A packed rectangle: either a child district (by key) or a type (by index into the node's types).
#[derive(Clone, Copy)]
enum Ref {
    Child(usize),
    Type(usize),
}

#[derive(Clone, Copy)]
struct Item {
    w: f64,
    d: f64,
    x: f64,
    z: f64,
    what: Ref,
}

pub fn layout(city_name: &str, types: &[TypeInfo]) -> Layout {
    let mut root = build_tree(city_name, types);
    measure(&mut root);
    let mut districts = vec![];
    let mut lots = HashMap::with_capacity(types.len());
    let (w, d) = (root.w, root.d);
    place(&root, -w / 2.0, -d / 2.0, 0, &mut districts, &mut lots);
    Layout { districts, lots, width: w, depth: d }
}

fn build_tree<'a>(city_name: &str, types: &'a [TypeInfo]) -> Node<'a> {
    let multi_module = {
        let mut modules: Vec<&str> = types.iter().map(|t| t.module.as_str()).collect();
        modules.sort_unstable();
        modules.dedup();
        modules.len() > 1
    };
    let mut root = Node::new(city_name.to_string(), String::new());
    for t in types {
        let mut node = &mut root;
        if multi_module {
            let module = if t.module.is_empty() { "(root)".to_string() } else { t.module.clone() };
            let key = format!("module:{module}");
            node = node.children.entry(key.clone()).or_insert_with(|| Node::new(module, key));
            node.module = true;
        }
        let mut segments: Vec<String> = t.package_segments().into_iter().map(str::to_string).collect();
        if segments.is_empty() {
            segments.push(if t.language == Language::Java { "(default)" } else { "(root)" }.to_string());
        }
        let sep = t.language.separator();
        let path = if node.id.is_empty() { String::new() } else { format!("{}/", node.id) };
        let mut fqn = String::new();
        for segment in segments {
            fqn = if fqn.is_empty() { segment.clone() } else { format!("{fqn}{sep}{segment}") };
            let id = format!("{path}{fqn}");
            node = node.children.entry(segment.clone()).or_insert_with(|| Node::new(segment, id));
            node.separator = sep;
        }
        node.types.push(t);
    }
    collapse(&mut root);
    root
}

/// `com → acme → shop` with nothing in between becomes a single `com.acme.shop` district.
fn collapse(node: &mut Node) {
    let children = std::mem::take(&mut node.children);
    for (_, child) in children {
        let mut c = child;
        while !c.module && c.types.is_empty() && c.children.len() == 1 {
            let only_is_module = c.children.values().next().unwrap().module;
            if only_is_module {
                break;
            }
            let (_, only) = c.children.into_iter().next().unwrap();
            let mut joined = Node::new(format!("{}{}{}", c.label, only.separator, only.label), only.id);
            joined.separator = only.separator;
            joined.children = only.children;
            joined.types = only.types;
            c = joined;
        }
        collapse(&mut c);
        node.children.insert(c.label.clone(), c);
    }
}

fn measure(node: &mut Node) {
    let mut items = vec![];
    for (i, child) in node.children.values_mut().enumerate() {
        measure(child);
        items.push(Item { w: child.w + GAP, d: child.d + GAP, x: 0.0, z: 0.0, what: Ref::Child(i) });
    }
    for (i, t) in node.types.iter().enumerate() {
        let s = side(t);
        items.push(Item { w: s + GAP, d: s + GAP, x: 0.0, z: 0.0, what: Ref::Type(i) });
    }
    let (w, d) = pack(&mut items);
    node.items = items;
    node.w = (w - GAP).max(0.0) + 2.0 * PAD;
    node.d = (d - GAP).max(0.0) + 2.0 * PAD;
}

fn place(node: &Node, left: f64, top: f64, depth: u32, districts: &mut Vec<District>, lots: &mut HashMap<String, Lot>) {
    let index = districts.len();
    districts.push(District {
        id: node.id.clone(),
        label: node.label.clone(),
        depth,
        x: left + node.w / 2.0,
        z: top + node.d / 2.0,
        w: node.w,
        d: node.d,
        types: node.type_count(),
    });
    let children: Vec<&Node> = node.children.values().collect();
    for item in &node.items {
        let x = left + PAD + item.x;
        let z = top + PAD + item.z;
        match item.what {
            Ref::Child(i) => place(children[i], x, z, depth + 1, districts, lots),
            Ref::Type(i) => {
                let t = node.types[i];
                let s = side(t);
                lots.insert(t.fqn.clone(), Lot { x: x + s / 2.0, z: z + s / 2.0, side: s, y: (depth + 1) as f64 * PLATFORM, district: index });
            }
        }
    }
}

/// Packs items into a compact rectangle; assigns item positions and returns (width, depth).
fn pack(items: &mut [Item]) -> (f64, f64) {
    if items.is_empty() {
        return (0.0, 0.0);
    }
    // largest first (stable, like Java's reversed comparator)
    items.sort_by(|a, b| {
        let ka = (a.w.max(a.d), a.w * a.d);
        let kb = (b.w.max(b.d), b.w * b.d);
        kb.0.total_cmp(&ka.0).then(kb.1.total_cmp(&ka.1))
    });
    let area: f64 = items.iter().map(|i| i.w * i.d).sum();
    let max_w = items.iter().map(|i| i.w).fold(0.0, f64::max);
    let mut xs = vec![0.0; items.len()];
    let mut zs = vec![0.0; items.len()];
    let mut best: Option<(Vec<f64>, Vec<f64>, (f64, f64))> = None;
    let mut best_score = f64::MAX;
    for f in [0.85, 1.0, 1.12, 1.25, 1.45, 1.7] {
        let width = max_w.max(area.sqrt() * f);
        let size = skyline(items, width, &mut xs, &mut zs);
        let score = size.0 * size.1 * (1.0 + 0.35 * (size.0 / size.1).ln().abs());
        if score < best_score {
            best_score = score;
            best = Some((xs.clone(), zs.clone(), size));
        }
    }
    let (bx, bz, size) = best.unwrap();
    for (i, item) in items.iter_mut().enumerate() {
        item.x = bx[i];
        item.z = bz[i];
    }
    size
}

/// Bottom-left skyline packing into a strip of the given width. Returns (usedWidth, depth).
fn skyline(items: &[Item], width: f64, xs: &mut [f64], zs: &mut [f64]) -> (f64, f64) {
    const EPS: f64 = 1e-9;
    // each segment: [x, width, height]
    let mut sky: Vec<[f64; 3]> = vec![[0.0, width, 0.0]];
    let mut used_w: f64 = 0.0;
    let mut used_d: f64 = 0.0;
    for (n, it) in items.iter().enumerate() {
        let mut best_idx = 0;
        let mut best_top = f64::MAX;
        let mut best_y = 0.0;
        for i in 0..sky.len() {
            let x = sky[i][0];
            if x + it.w > width + EPS {
                break;
            }
            let mut y: f64 = 0.0;
            let mut remaining = it.w;
            let mut j = i;
            while j < sky.len() && remaining > EPS {
                y = y.max(sky[j][2]);
                remaining -= sky[j][1];
                j += 1;
            }
            if y + it.d < best_top - EPS {
                best_top = y + it.d;
                best_idx = i;
                best_y = y;
            }
        }
        let x = sky[best_idx][0];
        xs[n] = x;
        zs[n] = best_y;
        used_w = used_w.max(x + it.w);
        used_d = used_d.max(best_y + it.d);

        let end = x + it.w;
        sky.insert(best_idx, [x, it.w, best_y + it.d]);
        let k = best_idx + 1;
        while k < sky.len() {
            let seg = sky[k];
            let seg_end = seg[0] + seg[1];
            if seg[0] >= end - EPS {
                break;
            }
            if seg_end <= end + EPS {
                sky.remove(k);
            } else {
                sky[k][1] = seg_end - end;
                sky[k][0] = end;
                break;
            }
        }
        let mut i = sky.len() - 1;
        while i > 0 {
            if (sky[i - 1][2] - sky[i][2]).abs() < EPS {
                sky[i - 1][1] += sky[i][1];
                sky.remove(i);
            }
            i -= 1;
        }
    }
    (used_w, used_d)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;
    use std::path::PathBuf;

    fn ty(pkg: &str, name: &str, noa: u32) -> TypeInfo {
        TypeInfo {
            language: Language::Java,
            fqn: format!("{pkg}.{name}"),
            name: name.into(),
            package: pkg.into(),
            module: String::new(),
            kind: "class",
            file: PathBuf::from(format!("{name}.java")),
            line: 1,
            end_line: 10,
            loc: 10,
            noa,
            test: false,
            methods: vec![],
            references: HashSet::new(),
            explicit_imports: vec![],
            wildcard_imports: vec![],
        }
    }

    fn city() -> Vec<TypeInfo> {
        let pkgs = ["a", "a.b", "a.b.c", "a.d", "e", "e.f.g"];
        (0..300).map(|i| ty(pkgs[(i * 7) % pkgs.len()], &format!("T{i}"), ((i * 13) % 9) as u32 * if i % 5 == 0 { 9 } else { 1 })).collect()
    }

    #[test]
    fn buildings_never_overlap_and_stay_inside_their_district() {
        let types = city();
        let l = layout("city", &types);
        assert_eq!(l.lots.len(), types.len());
        let lots: Vec<&Lot> = l.lots.values().collect();
        for (i, a) in lots.iter().enumerate() {
            let dd = &l.districts[a.district];
            assert!(a.x - a.side / 2.0 >= dd.x - dd.w / 2.0 - 1e-6 && a.x + a.side / 2.0 <= dd.x + dd.w / 2.0 + 1e-6);
            assert!(a.z - a.side / 2.0 >= dd.z - dd.d / 2.0 - 1e-6 && a.z + a.side / 2.0 <= dd.z + dd.d / 2.0 + 1e-6);
            for b in &lots[i + 1..] {
                let gx = (a.x - b.x).abs() - (a.side + b.side) / 2.0;
                let gz = (a.z - b.z).abs() - (a.side + b.side) / 2.0;
                assert!(gx >= GAP - 1e-6 || gz >= GAP - 1e-6);
            }
        }
    }

    #[test]
    fn single_child_chains_collapse() {
        let l = layout("city", &[ty("com.acme.shop", "Cart", 2)]);
        let labels: Vec<&str> = l.districts.iter().map(|d| d.label.as_str()).collect();
        assert_eq!(labels, vec!["city", "com.acme.shop"]);
    }
}
