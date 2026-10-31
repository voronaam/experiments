//! Lays the city out: packages become nested, stacked districts and types become buildings whose footprint grows
//! with their number of attributes. Each district's contents are packed with a skyline bin-packer, trying several
//! strip widths and keeping the most compact, square-ish result.
//!
//! This is the layout step of the pipeline (walk → analyse → link dependencies → git history → city model →
//! **layout** → JSON → viewers): [`crate::city`] calls [`layout`] with the analysed types and turns the result into
//! the city model that the JSON writer and the viewers use.
//!
//! # Coordinates
//! The city lies on the ground plane: `x` runs left to right and `z` runs from the back to the front. A city of width
//! `w` and depth `d` is centred on the origin. `y` is up and only matters for the platforms: a district at nesting
//! depth `n` is the `n`-th platform of the stack, and its buildings stand on top of it at `(n + 1) * PLATFORM`.
//!
//! # The three passes
//! 1. [`build_tree`] turns the flat list of types into a tree of districts (modules → packages → types) and
//!    collapses chains of empty packages.
//! 2. [`measure`] works bottom-up. A district can only be packed once the size of every child district is known, so
//!    children are measured first. Each district then packs its children and its own buildings as plain rectangles
//!    and so learns its own size. Positions are relative to the district's top-left corner.
//! 3. [`place`] works top-down, turning those relative positions into absolute ones and emitting the districts and
//!    building lots.
//!
//! # Streets
//! Every rectangle handed to the packer is [`GAP`] wider and deeper than the thing it holds. Packing those rectangles
//! edge to edge leaves a `GAP`-wide street to the right of and in front of every lot, so the packer itself never has
//! to think about spacing. The last street along each edge is not needed, so [`measure`] subtracts one `GAP` again
//! and adds [`PAD`] on every side for the kerb.
//!
//! The layout is deterministic: districts are kept in [`BTreeMap`]s and the sort in [`pack`] is stable, so the same
//! input always yields the same city. This is a faithful port of the Java `CityLayout`; the Java and D editions
//! implement exactly the same algorithm and produce the same coordinates.

use crate::model::{Language, TypeInfo};
use std::collections::{BTreeMap, HashMap};

/// Thickness of one district platform.
pub const PLATFORM: f64 = 0.4;
/// Street width between neighbouring lots.
pub const GAP: f64 = 0.8;
/// Kerb between a district's edge and its contents.
pub const PAD: f64 = 1.0;

/// One platform. `x`/`z` is its centre, `w`/`d` its size, `depth` its nesting level (0 is the city itself) and
/// `types` the number of types in it and all its sub-districts.
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
    /// Index into [`Layout::districts`] of the district the building stands on.
    pub district: usize,
}

/// The laid-out city, `width` × `depth` in size.
pub struct Layout {
    /// In pre-order: every district comes before its sub-districts, the city first. This is also the order they are
    /// drawn in; [`Lot::district`] points into it.
    pub districts: Vec<District>,
    /// Keyed by the type's fully qualified name ([`TypeInfo::fqn`]).
    pub lots: HashMap<String, Lot>,
    pub width: f64,
    pub depth: f64,
}

/// Side length of a building's square footprint. It grows with the square root of the attribute count, so the
/// footprint area grows roughly linearly with it, and even a type without attributes gets a visible 1.2 × 1.2 lot.
/// The history replay also sizes buildings with it, for each snapshot's attribute count.
pub fn side_of_noa(noa: u32) -> f64 {
    1.2 + 0.8 * (noa as f64).sqrt()
}

/// A district while the layout is being computed. It borrows the types (`'a` is the lifetime of the slice passed to
/// [`layout`]) rather than cloning them.
struct Node<'a> {
    /// Name shown in the viewer, e.g. `shop` or, after collapsing, `com.acme.shop`.
    label: String,
    /// Stable identifier: the module path plus the fully qualified package name ("" for the city).
    id: String,
    /// Sub-districts by label. A sorted map keeps the layout independent of the order types were found in, and its
    /// iteration order is what the indices in [`Ref::Child`] count in.
    children: BTreeMap<String, Node<'a>>,
    /// Types declared directly in this district (not in its sub-districts), each with its footprint side.
    types: Vec<(&'a TypeInfo, f64)>,
    /// A build module (Maven/Gradle module, npm project, crate). Modules are never merged into their parent.
    module: bool,
    /// How this district's name joins with a collapsed child's: ".", "/" or "::".
    separator: &'static str,
    /// Size of the platform including its kerb; set by [`measure`].
    w: f64,
    d: f64,
    /// The packed contents, with positions relative to the inside of the kerb; set by [`measure`].
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

/// What a packed rectangle stands for: a child district (by its index in the iteration order of the node's
/// `children` map) or a type (by index into the node's `types`). Indices rather than references keep [`Item`] `Copy`
/// and free of borrows, and stay valid when [`pack`] reorders the items.
#[derive(Clone, Copy)]
enum Ref {
    Child(usize),
    Type(usize),
}

/// A rectangle for the packer. `w`/`d` include the street ([`GAP`]); `x`/`z` is the top-left corner the packer
/// chose.
#[derive(Clone, Copy)]
struct Item {
    w: f64,
    d: f64,
    x: f64,
    z: f64,
    what: Ref,
}

/// Lays out `types` as a city named `city_name` (the label of the root district).
pub fn layout(city_name: &str, types: &[TypeInfo]) -> Layout {
    layout_with_noa(city_name, types, |t| t.noa)
}

/// [`layout`], with each footprint sized for the attribute count `noa(t)` instead of `t.noa`. The history replay
/// passes a type's largest count over the present and every snapshot, so the lot fits the building at its widest.
/// A [`Lot`] still has the type's own footprint side, centred in the larger lot.
pub fn layout_with_noa(city_name: &str, types: &[TypeInfo], noa: impl Fn(&TypeInfo) -> u32) -> Layout {
    let mut root = build_tree(city_name, types, &noa);
    measure(&mut root);
    let mut districts = vec![];
    let mut lots = HashMap::with_capacity(types.len());
    let (w, d) = (root.w, root.d);
    // Start at the top-left corner that puts the whole city's centre on the origin.
    place(&root, -w / 2.0, -d / 2.0, 0, &mut districts, &mut lots);
    Layout { districts, lots, width: w, depth: d }
}

/// Builds the district tree. With more than one build module, the first level is one district per module, so that
/// packages with the same name in different modules stay apart. Below that, each package segment is one level:
/// `com.acme.shop` becomes `com → acme → shop`, and the type is added to the last one.
fn build_tree<'a>(city_name: &str, types: &'a [TypeInfo], noa: &dyn Fn(&TypeInfo) -> u32) -> Node<'a> {
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
            // The "module:" prefix keeps a module apart from a top-level package of the same name. It serves as both
            // the map key and the id.
            let key = format!("module:{module}");
            node = node.children.entry(key.clone()).or_insert_with(|| Node::new(module, key));
            node.module = true;
        }
        let mut segments: Vec<String> = t.package_segments().into_iter().map(str::to_string).collect();
        // Types outside any package still need a district to stand in.
        if segments.is_empty() {
            segments.push(if t.language == Language::Java { "(default)" } else { "(root)" }.to_string());
        }
        // Ids look like "<module>/<package>", with the package written the way its language writes it:
        // com.acme.shop (Java), src/shop (TypeScript) or crate::shop (Rust).
        let sep = t.language.separator();
        let path = if node.id.is_empty() { String::new() } else { format!("{}/", node.id) };
        let mut fqn = String::new();
        for segment in segments {
            fqn = if fqn.is_empty() { segment.clone() } else { format!("{fqn}{sep}{segment}") };
            let id = format!("{path}{fqn}");
            node = node.children.entry(segment.clone()).or_insert_with(|| Node::new(segment, id));
            node.separator = sep;
        }
        node.types.push((t, side_of_noa(noa(t))));
    }
    collapse(&mut root);
    root
}

/// `com → acme → shop` with nothing in between becomes a single `com.acme.shop` district.
///
/// Without this, a typical Java project would be a tower of empty platforms, one per package segment, before the
/// first building appears. A district is merged with its only child when it has no types of its own. Modules are
/// never merged, neither into their parent nor with their child, so every module keeps its own platform.
///
/// The merged district takes the child's id, because that is the package that actually holds the code.
fn collapse(node: &mut Node) {
    // Take the children out so they can be consumed by value and re-inserted under their new labels.
    let children = std::mem::take(&mut node.children);
    for (_, child) in children {
        let mut c = child;
        // Keep folding while c is an empty pass-through: com → acme → shop → (types) ends as "com.acme.shop".
        while !c.module && c.types.is_empty() && c.children.len() == 1 {
            // Peek first: moving the only child out of `c` is only allowed once we know it will be merged.
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
        // The chain's last district may still have chains of its own further down.
        collapse(&mut c);
        // Re-key by the new label (e.g. "com.acme.shop") so districts stay sorted by what the viewer shows.
        node.children.insert(c.label.clone(), c);
    }
}

/// Sizes every district bottom-up: a district's size depends on its children's, so they are measured first. Both
/// sub-districts and buildings go into one packing, which is why buildings and sub-districts mix freely on a
/// platform.
///
/// Children are numbered in the `children` map's iteration order; [`place`] relies on the map not changing between
/// the two passes.
fn measure(node: &mut Node) {
    let mut items = vec![];
    for (i, child) in node.children.values_mut().enumerate() {
        measure(child);
        items.push(Item { w: child.w + GAP, d: child.d + GAP, x: 0.0, z: 0.0, what: Ref::Child(i) });
    }
    for (i, &(_, s)) in node.types.iter().enumerate() {
        items.push(Item { w: s + GAP, d: s + GAP, x: 0.0, z: 0.0, what: Ref::Type(i) });
    }
    let (w, d) = pack(&mut items);
    node.items = items;
    // Drop the street after the last lot in each direction and add the kerb on both sides. An empty district (size 0)
    // must not go negative, hence the max.
    node.w = (w - GAP).max(0.0) + 2.0 * PAD;
    node.d = (d - GAP).max(0.0) + 2.0 * PAD;
}

/// Turns the relative positions from [`measure`] into absolute ones, top-down. `left`/`top` is where this district's
/// top-left corner ends up.
///
/// Recording the district before recursing gives `districts` its pre-order, and `index` is this district's position in
/// it. Sub-districts are visited in packing order (largest first, see [`pack`]), not alphabetically.
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
    // A BTreeMap cannot be indexed by position, so collect the children in the same order measure numbered them.
    let children: Vec<&Node> = node.children.values().collect();
    for item in &node.items {
        // The packer worked inside the kerb, so shift by PAD as well as by the district's corner.
        let x = left + PAD + item.x;
        let z = top + PAD + item.z;
        match item.what {
            Ref::Child(i) => place(children[i], x, z, depth + 1, districts, lots),
            Ref::Type(i) => {
                // The item is the footprint plus the street; the building sits in its top-left corner, and the lot
                // stores the footprint's centre. It stands on this district's platform, one level above depth.
                // With a larger lot (history replay), the building keeps its own side, centred in the lot, as in the
                // Java and D editions.
                let (t, s) = node.types[i];
                lots.insert(t.fqn.clone(), Lot { x: x + s / 2.0, z: z + s / 2.0, side: side_of_noa(t.noa), y: (depth + 1) as f64 * PLATFORM, district: index });
            }
        }
    }
}

/// Packs items into a compact rectangle; assigns item positions and returns (width, depth).
///
/// The skyline packer below needs a fixed strip width, but the best width is not known in advance. The strip must be
/// at least as wide as the widest item. If it is much narrower than `√area`, the result is a tall column; if it is
/// much wider, a flat row. So this tries six widths around `√area` (the side of a square that would hold every item
/// without any waste) and keeps the best result.
///
/// "Best" means small and square. The score is the bounding box's area, with a penalty that grows with how far the
/// aspect ratio is from 1:1. `|ln(w/d)|` treats 2:1 and 1:2 alike and is 0 for a square, so a 2:1 rectangle costs
/// about 24% more than a square of the same area.
///
/// Note that the sort changes the order of `items`; positions are written back in that sorted order. [`Ref`] indices
/// are unaffected, since they identify the child or type, not the item's position.
fn pack(items: &mut [Item]) -> (f64, f64) {
    if items.is_empty() {
        return (0.0, 0.0);
    }
    // Largest first: by longest side, then by area, both descending (b compared with a). Small items placed last can
    // fill the low spots the big ones leave in the skyline; placed the other way round, big items land on a ragged
    // skyline and waste space. sort_by is stable, so equal items keep their order, exactly like Java's List.sort with
    // a reversed comparator; total_cmp gives f64 the total order sort_by needs.
    items.sort_by(|a, b| {
        let ka = (a.w.max(a.d), a.w * a.d);
        let kb = (b.w.max(b.d), b.w * b.d);
        kb.0.total_cmp(&ka.0).then(kb.1.total_cmp(&ka.1))
    });
    let area: f64 = items.iter().map(|i| i.w * i.d).sum();
    let max_w = items.iter().map(|i| i.w).fold(0.0, f64::max);
    let mut xs = vec![0.0; items.len()];
    let mut zs = vec![0.0; items.len()];
    // Each attempt writes its positions into xs/zs; the best attempt's positions and size are copied aside.
    let mut best: Option<(Vec<f64>, Vec<f64>, (f64, f64))> = None;
    let mut best_score = f64::MAX;
    // Most factors are above 1: the packer always leaves some gaps, so the result needs more room than the bare item
    // area, and a slightly wider strip is more likely to come out square.
    for f in [0.85, 1.0, 1.12, 1.25, 1.45, 1.7] {
        let width = max_w.max(area.sqrt() * f);
        let size = skyline(items, width, &mut xs, &mut zs);
        let score = size.0 * size.1 * (1.0 + 0.35 * (size.0 / size.1).ln().abs());
        // Strictly less: on a tie the earlier (narrower) width wins, which keeps the result deterministic.
        if score < best_score {
            best_score = score;
            best = Some((xs.clone(), zs.clone(), size));
        }
    }
    // The first attempt always beats f64::MAX (the score is finite: items are non-empty and at least GAP in size), so
    // best is set.
    let (bx, bz, size) = best.unwrap();
    for (i, item) in items.iter_mut().enumerate() {
        item.x = bx[i];
        item.z = bz[i];
    }
    size
}

/// Bottom-left skyline packing into a strip of the given width. Returns (usedWidth, depth).
///
/// Picture the strip as a box `width` wide and infinitely deep, filled from the back (`z = 0`) forwards, like
/// stacking boxes against a wall. Only the front edge of what has been placed so far matters: the skyline. It is
/// stored as a list of segments, ordered by `x`, that cover `[0, width]` without gaps or overlaps. Each segment is
/// `[x, width, height]`, where height is how far forward (`z`) things already reach over that stretch.
///
/// ```text
///   x →  0          4      6        10
///  z  ┌──────────┬──────┬──────────┐
///  ↓  │  item A  │  B   │  item C  │
///     │          ├──────┘          │     skyline: [0,4,3] [4,2,1] [6,4,3]
///     └──────────┘      └──────────┘
/// ```
///
/// Each item, in order, goes where its front edge (`z + depth`) ends up furthest back. Only the left ends of segments
/// are tried as positions: an item starting there rests on the highest segment it spans. On ties the leftmost
/// position wins. Positions are written to `xs`/`zs`, indexed like `items`.
///
/// Items never go under an overhang: the region behind the skyline is treated as full even where it has holes. That
/// wastes a little space, but keeps the algorithm simple and fast (O(n · segments) per item).
fn skyline(items: &[Item], width: f64, xs: &mut [f64], zs: &mut [f64]) -> (f64, f64) {
    // Coordinates are sums of f64s, so compare with a tolerance; otherwise a rounding error could make an item that
    // exactly fills a gap look too big for it.
    const EPS: f64 = 1e-9;
    // Each segment: [x, width, height]. Initially the floor of the whole strip, at height 0.
    let mut sky: Vec<[f64; 3]> = vec![[0.0, width, 0.0]];
    // Bounding box of everything placed so far.
    let mut used_w: f64 = 0.0;
    let mut used_d: f64 = 0.0;
    for (n, it) in items.iter().enumerate() {
        // Java starts at -1; 0 is equivalent here because segment 0 always fits (see below) and so always wins first.
        let mut best_idx = 0;
        let mut best_top = f64::MAX;
        let mut best_y = 0.0;
        // Try the item's left edge at the start of each segment.
        for i in 0..sky.len() {
            let x = sky[i][0];
            // Segments are ordered by x, so once the item sticks out on the right it does so for every later segment
            // too. Segment 0 always fits, because the strip is at least as wide as the widest item.
            if x + it.w > width + EPS {
                break;
            }
            // The item rests on the highest of the segments under it: walk right until its width is covered.
            let mut y: f64 = 0.0;
            let mut remaining = it.w;
            let mut j = i;
            while j < sky.len() && remaining > EPS {
                y = y.max(sky[j][2]);
                remaining -= sky[j][1];
                j += 1;
            }
            // Keep the position whose front edge is furthest back. Strictly less (by more than EPS) means a later
            // position must be really better, so ties go to the leftmost.
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

        // Update the skyline. The item adds a new segment covering [x, end) at its front edge. It goes in before
        // segment best_idx (which starts at x too); then the segments it now covers are removed or shortened. k never
        // advances: removing segment k shifts the next one into its place.
        let end = x + it.w;
        sky.insert(best_idx, [x, it.w, best_y + it.d]);
        let k = best_idx + 1;
        while k < sky.len() {
            let seg = sky[k];
            let seg_end = seg[0] + seg[1];
            // Starts at or after the item's right edge: nothing more to cover.
            if seg[0] >= end - EPS {
                break;
            }
            // Completely under the item: gone. Otherwise it sticks out on the right: keep only that part.
            if seg_end <= end + EPS {
                sky.remove(k);
            } else {
                sky[k][1] = seg_end - end;
                sky[k][0] = end;
                break;
            }
        }
        // Merge neighbours of equal height. Fewer segments make later searches faster, and a wide flat segment lets a
        // wide item start at its left end. Walking backwards keeps indices valid while removing. The skyline always
        // has at least one segment, so len() - 1 cannot underflow.
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
    //! Layout rules, one per test. The Java (`CityLayoutTest`) and D (`layout.d`) editions run the same scenarios on
    //! the same synthetic fixtures with the same expectations.

    use super::*;
    use std::collections::HashSet;
    use std::path::PathBuf;

    /// A type of the given language in package `pkg` and build module `module`.
    fn ty_in(language: Language, module: &str, pkg: &str, name: &str, noa: u32) -> TypeInfo {
        TypeInfo {
            language,
            fqn: if pkg.is_empty() { name.to_string() } else { format!("{pkg}{}{name}", language.separator()) },
            name: name.into(),
            package: pkg.into(),
            module: module.into(),
            kind: "class",
            file: PathBuf::from(format!("{name}.java")),
            line: 1,
            end_line: 10,
            loc: 10,
            noa,
            test: false,
            methods: vec![],
            references: HashSet::new(),
            explicit_imports: Default::default(),
            wildcard_imports: Default::default(),
        }
    }

    fn ty(pkg: &str, name: &str, noa: u32) -> TypeInfo {
        ty_in(Language::Java, "", pkg, name, noa)
    }

    /// The shared fixture of all three editions: 300 Java types spread evenly over six packages (two of them only
    /// reachable through empty chains), mostly small, every fifth one larger.
    fn city() -> Vec<TypeInfo> {
        let pkgs = ["a", "a.b", "a.b.c", "a.d", "e", "e.f.g"];
        (0..300).map(|i| ty(pkgs[(i * 7) % pkgs.len()], &format!("T{i}"), ((i * 13) % 9) as u32 * if i % 5 == 0 { 9 } else { 1 })).collect()
    }

    fn labels(l: &Layout) -> Vec<&str> {
        l.districts.iter().map(|d| d.label.as_str()).collect()
    }

    fn ids(l: &Layout) -> Vec<&str> {
        l.districts.iter().map(|d| d.id.as_str()).collect()
    }

    fn depths(l: &Layout) -> Vec<u32> {
        l.districts.iter().map(|d| d.depth).collect()
    }

    /// Rounds to 9 decimals, so exact expectations are not upset by floating-point noise.
    fn round(v: f64) -> f64 {
        (v * 1e9).round() / 1e9
    }

    /// Every type of the input gets exactly one lot, keyed by its fqn.
    #[test]
    fn every_type_gets_a_lot() {
        let types = city();
        let l = layout("city", &types);
        assert_eq!(l.lots.len(), types.len());
        assert!(types.iter().all(|t| l.lots.contains_key(&t.fqn)));
    }

    /// Buildings never overlap: any two lots are at least a street (GAP) apart along x or z.
    #[test]
    fn buildings_in_the_same_district_do_not_overlap() {
        let l = layout("city", &city());
        let lots: Vec<&Lot> = l.lots.values().collect();
        for (i, a) in lots.iter().enumerate() {
            for b in &lots[i + 1..] {
                let gx = (a.x - b.x).abs() - (a.side + b.side) / 2.0;
                let gz = (a.z - b.z).abs() - (a.side + b.side) / 2.0;
                assert!(gx >= GAP - 1e-6 || gz >= GAP - 1e-6, "{a:?} and {b:?} overlap");
            }
        }
    }

    /// A building stands inside its district with at least the kerb (PAD) to every edge, on top of the district's
    /// platform: `y = (depth + 1) * PLATFORM`.
    #[test]
    fn buildings_stand_inside_their_district() {
        let l = layout("city", &city());
        let pad = PAD - 1e-6;
        for a in l.lots.values() {
            let d = &l.districts[a.district];
            assert!(a.x - a.side / 2.0 >= d.x - d.w / 2.0 + pad && a.x + a.side / 2.0 <= d.x + d.w / 2.0 - pad);
            assert!(a.z - a.side / 2.0 >= d.z - d.d / 2.0 + pad && a.z + a.side / 2.0 <= d.z + d.d / 2.0 - pad);
            assert!((a.y - (d.depth + 1) as f64 * PLATFORM).abs() < 1e-9);
        }
    }

    /// The parent of every district: the closest earlier district one level up (they are in pre-order).
    fn parents(ds: &[District]) -> Vec<usize> {
        let mut parent = vec![usize::MAX; ds.len()];
        for i in 1..ds.len() {
            let mut p = i - 1;
            while ds[p].depth + 1 != ds[i].depth {
                p -= 1;
            }
            parent[i] = p;
        }
        parent
    }

    /// A sub-district lies inside its parent with at least the kerb to every edge, and sibling districts are at
    /// least a street apart.
    #[test]
    fn sub_districts_stand_inside_their_parent_with_streets_between() {
        let l = layout("city", &city());
        let ds = &l.districts;
        let parent = parents(ds);
        let pad = PAD - 1e-6;
        for i in 1..ds.len() {
            let (c, p) = (&ds[i], &ds[parent[i]]);
            assert!(c.x - c.w / 2.0 >= p.x - p.w / 2.0 + pad && c.x + c.w / 2.0 <= p.x + p.w / 2.0 - pad, "{}", c.label);
            assert!(c.z - c.d / 2.0 >= p.z - p.d / 2.0 + pad && c.z + c.d / 2.0 <= p.z + p.d / 2.0 - pad, "{}", c.label);
            for j in i + 1..ds.len() {
                if parent[j] == parent[i] {
                    let s = &ds[j];
                    let gx = (c.x - s.x).abs() - (c.w + s.w) / 2.0;
                    let gz = (c.z - s.z).abs() - (c.d + s.d) / 2.0;
                    assert!(gx >= GAP - 1e-6 || gz >= GAP - 1e-6, "{} and {} overlap", c.label, s.label);
                }
            }
        }
    }

    /// An empty package chain above the only package collapses into one district named like the package.
    #[test]
    fn single_child_package_chains_are_collapsed() {
        let l = layout("city", &[ty("com.acme.shop", "Cart", 2)]);
        assert_eq!(labels(&l), ["city", "com.acme.shop"]);
        assert_eq!(ids(&l), ["", "com.acme.shop"]);
    }

    /// A packed district comes out roughly square, not as a long row or column.
    #[test]
    fn city_is_roughly_square() {
        // A fixed permutation of 0..399 (37 is coprime with 400), so the input is not in size order.
        let types: Vec<TypeInfo> = (0..400).map(|i| i * 37 % 400).map(|k| ty("p", &format!("T{k}"), k % 7)).collect();
        let l = layout("city", &types);
        let aspect = l.width / l.depth;
        assert!((0.5..=2.0).contains(&aspect), "aspect {aspect}");
    }

    /// The lots as "x z side district" strings, sorted: the set of places regardless of which type got which.
    fn sorted_lots(l: &Layout) -> Vec<String> {
        let mut v: Vec<String> = l.lots.values().map(|a| format!("{} {} {} {}", a.x, a.z, a.side, a.district)).collect();
        v.sort();
        v
    }

    fn district_key(l: &Layout) -> Vec<String> {
        l.districts.iter().map(|d| format!("{:?}", d)).collect()
    }

    /// The same input always gives exactly the same city. Another input order gives the same districts and the same
    /// set of lots; only equally sized buildings may swap places, as the packer keeps their input order (the analysis
    /// always hands the types over sorted by fqn).
    #[test]
    fn layout_is_deterministic() {
        let a = layout("city", &city());
        let again = layout("city", &city());
        assert_eq!(district_key(&a), district_key(&again));
        let mut fqns: Vec<&String> = a.lots.keys().collect();
        fqns.sort();
        for f in fqns {
            assert_eq!(format!("{:?}", a.lots[f]), format!("{:?}", again.lots[f]));
        }
        let mut reversed = city();
        reversed.reverse();
        let b = layout("city", &reversed);
        assert_eq!(district_key(&a), district_key(&b));
        assert_eq!((a.width, a.depth), (b.width, b.depth));
        assert_eq!(sorted_lots(&a), sorted_lots(&b));
    }

    /// The footprint side is `1.2 + 0.8 * sqrt(noa)`: 1.2 without attributes.
    #[test]
    fn footprint_grows_with_the_square_root_of_the_attributes() {
        for (noa, side) in [(0, 1.2), (1, 2.0), (4, 2.8), (9, 3.6)] {
            assert!((side_of_noa(noa) - side).abs() < 1e-12, "{noa}");
        }
    }

    /// Without types the city is a single bare platform: just the kerb on both sides, centred on the origin.
    #[test]
    fn empty_city_is_one_bare_district() {
        let l = layout("city", &[]);
        assert_eq!(l.districts.len(), 1);
        let d = &l.districts[0];
        assert_eq!((d.id.as_str(), d.label.as_str(), d.depth, d.x, d.z, d.w, d.d, d.types), ("", "city", 0, 0.0, 0.0, 2.0 * PAD, 2.0 * PAD, 0));
        assert!(l.lots.is_empty());
        assert_eq!((l.width, l.depth), (2.0 * PAD, 2.0 * PAD));
    }

    /// Districts come in pre-order (each after its parent, siblings in packing order: larger first), with their
    /// depth, their package as id and the number of types in their whole subtree. `e.f.g` has an empty `f` in
    /// between, which is folded into `f.g`.
    #[test]
    fn districts_are_pre_order_with_depths_ids_and_subtree_type_counts() {
        let l = layout("city", &city());
        assert_eq!(labels(&l), ["city", "a", "b", "c", "d", "e", "f.g"]);
        assert_eq!(ids(&l), ["", "a", "a.b", "a.b.c", "a.d", "e", "e.f.g"]);
        assert_eq!(depths(&l), [0, 1, 2, 3, 2, 1, 2]);
        assert_eq!(l.districts.iter().map(|d| d.types).collect::<Vec<_>>(), [300, 200, 100, 50, 50, 100, 50]);
    }

    /// A collapsed chain is joined with its language's own separator: "/" for TypeScript, "::" for Rust.
    #[test]
    fn collapsed_chains_join_with_their_language_separator() {
        let ts = layout("city", &[ty_in(Language::TypeScript, "", "src/ui/forms", "F", 0)]);
        assert_eq!(labels(&ts), ["city", "src/ui/forms"]);
        assert_eq!(ids(&ts), ["", "src/ui/forms"]);
        let rust = layout("city", &[ty_in(Language::Rust, "", "net::http", "Get", 0)]);
        assert_eq!(labels(&rust), ["city", "net::http"]);
        assert_eq!(ids(&rust), ["", "net::http"]);
    }

    /// A package that holds types itself is not folded into its only sub-package.
    #[test]
    fn district_with_own_types_is_not_folded() {
        let l = layout("city", &[ty("a", "A", 0), ty("a.b", "B", 0)]);
        assert_eq!(labels(&l), ["city", "a", "b"]);
        assert_eq!(ids(&l), ["", "a", "a.b"]);
        assert_eq!((l.lots["a.A"].district, l.lots["a.b.B"].district), (1, 2));
    }

    /// With several build modules, each gets a district on the first level ("(root)" for the root module ""), whose
    /// id starts with `module:`; a module is never folded into its parent nor with its only child, though the chains
    /// below it are.
    #[test]
    fn modules_get_their_own_district_and_are_never_folded() {
        let l = layout("city", &[ty_in(Language::Java, "core", "x.y", "X", 0), ty_in(Language::Java, "", "z", "Z", 0)]);
        assert_eq!(labels(&l), ["city", "(root)", "z", "core", "x.y"]);
        assert_eq!(ids(&l), ["", "module:(root)", "module:(root)/z", "module:core", "module:core/x.y"]);
        assert_eq!(depths(&l), [0, 1, 2, 1, 2]);
        assert_eq!(l.lots["x.y.X"].district, 4);
    }

    /// With a single build module there is no module level: the packages sit directly on the city.
    #[test]
    fn single_module_has_no_module_level() {
        let l = layout("city", &[ty_in(Language::Java, "core", "x.y", "X", 0), ty_in(Language::Java, "core", "x.w", "W", 0)]);
        assert_eq!(labels(&l), ["city", "x", "w", "y"]);
        assert_eq!(ids(&l), ["", "x", "x.w", "x.y"]);
    }

    /// Types outside any package stand in "(default)" (Java's default package) or "(root)" (TypeScript, Rust).
    #[test]
    fn types_outside_a_package_stand_in_a_default_district() {
        let l = layout("city", &[ty_in(Language::Java, "", "", "J", 0), ty_in(Language::TypeScript, "", "", "T", 0), ty_in(Language::Rust, "", "", "R", 0)]);
        // "(root)" holds two buildings, so it is packed (and listed) first.
        assert_eq!(labels(&l), ["city", "(root)", "(default)"]);
        assert_eq!(ids(&l), ["", "(root)", "(default)"]);
        assert_eq!((l.lots["J"].district, l.lots["T"].district, l.lots["R"].district), (2, 1, 1));
    }

    /// Lots sized for a larger attribute count (as the history replay does with a type's largest count) are placed
    /// exactly as if the types had that count, but each building keeps its own footprint, centred in its lot.
    #[test]
    fn larger_lots_centre_the_buildings_own_footprint() {
        let own = [ty("p", "A", 0), ty("p", "B", 1), ty("q", "C", 4)];
        let grown = [ty("p", "A", 9), ty("p", "B", 4), ty("q", "C", 4)];
        let wide = layout_with_noa("city", &own, |t| match t.noa { 0 => 9, 1 => 4, n => n });
        let reference = layout("city", &grown);
        assert_eq!(district_key(&wide), district_key(&reference));
        for t in &own {
            let (w, g) = (&wide.lots[&t.fqn], &reference.lots[&t.fqn]);
            assert!((w.x - g.x).abs() < 1e-9 && (w.z - g.z).abs() < 1e-9, "{}", t.fqn);
            assert!((w.side - side_of_noa(t.noa)).abs() < 1e-12, "{}", t.fqn);
            assert_eq!(w.district, g.district);
        }
    }

    /// Pins the packer on a tiny case: the bigger building goes first at the back-left corner, the smaller one in
    /// front of it (a wider strip would put it beside, but the score ties and the narrower strip wins), and the city
    /// is centred on the origin.
    #[test]
    fn two_buildings_have_exact_coordinates() {
        let l = layout("city", &[ty("p", "A", 0), ty("p", "B", 9)]);
        assert_eq!((round(l.width), round(l.depth)), (7.6, 9.6));
        let p = &l.districts[1];
        assert_eq!([round(p.x), round(p.z), round(p.w), round(p.d)], [0.0, 0.0, 5.6, 7.6]);
        let (b, a) = (&l.lots["p.B"], &l.lots["p.A"]);
        assert_eq!([round(b.x), round(b.z), round(b.side), round(b.y)], [0.0, -1.0, 3.6, 0.8]);
        assert_eq!([round(a.x), round(a.z), round(a.side), round(a.y)], [-1.2, 2.2, 1.2, 0.8]);
    }
}
