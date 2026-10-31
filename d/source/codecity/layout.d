/// Lays the city out: packages become nested, stacked districts and types become buildings whose footprint grows
/// with their number of attributes. Each district's contents are packed with a skyline bin-packer, trying several
/// strip widths and keeping the most compact, square-ish result. (A faithful port of the Java/Rust editions.)
///
/// In the pipeline this runs once per build, after analysis (see `CityModel.build` in `codecity.city`): it takes
/// the analysed types and returns districts and building lots, which `codecity.city` combines with the metrics into
/// the city model that the viewers draw.
///
/// Coordinates:
/// The city lies on the ground plane: `x` runs left to right and `z` runs from the back to the front. A city of
/// width `w` and depth `d` is centred on the origin. `y` is up and only matters for the platforms: a district at
/// nesting depth `n` is the `n`-th platform of the stack, and its buildings stand on top of it at
/// `(n + 1) * platform`.
///
/// The three passes:
/// 1. `buildTree` turns the flat list of types into a tree of districts (modules → packages → types) and collapses
///    chains of empty packages.
/// 2. `measure` works bottom-up. A district can only be packed once the size of every child district is known, so
///    children are measured first. Each district then packs its children and its own buildings as plain rectangles
///    and so learns its own size. Positions are relative to the district's top-left corner.
/// 3. `place` works top-down, turning those relative positions into absolute ones and emitting the districts and
///    building lots.
///
/// Streets:
/// Every rectangle handed to the packer is `gap` wider and deeper than the thing it holds. Packing those rectangles
/// edge to edge leaves a `gap`-wide street to the right of and in front of every lot, so the packer itself never
/// has to think about spacing. The last street along each edge is not needed, so `measure` subtracts one `gap`
/// again and adds `pad` on every side for the kerb.
///
/// The layout is deterministic: children are always visited in sorted key order (`Node.sortedChildren`; a D
/// associative array has no defined order of its own) and the sort in `pack` is stable, so the same input always
/// yields the same city. The Java and Rust editions implement exactly the same algorithm and produce the same
/// coordinates.
module codecity.layout;

import codecity.model;
import std.algorithm : max, min, sort, SwapStrategy;
import std.math : abs, log, sqrt;

/// Thickness of one district platform.
enum double platform = 0.4;
/// Street width between neighbouring lots.
enum double gap = 0.8;
/// Kerb between a district's edge and its contents.
enum double pad = 1.0;

/// One platform. `x`/`z` is its centre, `w`/`d` its size, `depth` its nesting level (0 is the city itself) and
/// `types` the number of types in it and all its sub-districts.
struct District
{
    string id;
    string label;
    uint depth;
    double x, z, w, d;
    uint types;
}

/// Where a building stands: footprint centre, side length and the height of the ground it stands on.
struct Lot
{
    double x, z, side, y;
    /// Index into `Layout.districts` of the district the building stands on.
    size_t district;
}

/// The laid-out city. `districts` is in pre-order (every district comes before its sub-districts, the city first),
/// which is also the order they are drawn in; `Lot.district` points into it. `lots` is keyed by the type's fully
/// qualified name. `width`/`depth` is the size of the whole city (the root district).
struct Layout
{
    District[] districts;
    Lot[string] lots;
    double width, depth;
}

/// Side length of a building's square footprint. It grows with the square root of the attribute count, so the
/// footprint area grows roughly linearly with it, and even a type without attributes gets a visible 1.2 × 1.2 lot.
double side(ref const TypeDecl t) pure nothrow @safe
{
    return side(t.noa);
}

/// ditto, for an attribute count alone. The history replay uses this to size a building at each snapshot, and the
/// layout to size lots by a type's largest attribute count over its history (see `layout`).
double side(uint noa) pure nothrow @safe
{
    return 1.2 + 0.8 * sqrt(cast(double) noa);
}

/// A district while the layout is being computed.
private final class Node
{
    /// `label`: name shown in the viewer, e.g. `shop` or, after collapsing, `com.acme.shop`.
    /// `id`: stable identifier, the module path plus the fully qualified package name ("" for the city).
    string label, id;
    /// Sub-districts. Before `collapse` they are keyed by package segment (or `module:<name>` for modules), after it
    /// by label. An associative array has no stable order, so every walk that affects the result goes through
    /// `sortedChildren`.
    Node[string] children;
    /// Types declared directly in this district (not in its sub-districts). These point into the `types` array
    /// passed to `layout`, which outlives the whole computation.
    const(TypeDecl)*[] types;
    /// The footprint side of each of `types`, same order (see `layout` for when it is not just `side(*t)`).
    double[] sides;
    /// A build module (Maven/Gradle module, npm project, crate). Modules are never merged into their parent.
    bool module_;
    /// How this district's name joins with a collapsed child's: ".", "/" or "::".
    string separator = ".";
    /// Size of the platform including its kerb; set by `measure`.
    double w = 0, d = 0;
    /// The packed contents, with positions relative to the inside of the kerb; set by `measure`.
    Item[] items;

    this(string label, string id)
    {
        this.label = label;
        this.id = id;
    }

    /// The children ordered by key. `Item.index` of a child item is an index into this array, which stays valid
    /// because `children` is not changed after `collapse`.
    Node[] sortedChildren()
    {
        auto keys = children.keys;
        keys.sort();
        Node[] result;
        foreach (k; keys) result ~= children[k];
        return result;
    }

    uint typeCount()
    {
        uint n = cast(uint) types.length;
        foreach (c; children) n += c.typeCount;
        return n;
    }
}

/// A rectangle for the packer: a child district (`isChild`, `index` into `Node.sortedChildren`) or a building
/// (`index` into `Node.types`). `w`/`d` include the street (`gap`); `x`/`z` is the top-left corner the packer chose.
private struct Item
{
    double w, d, x = 0, z = 0;
    bool isChild;
    size_t index;
}

/// Lays out `types` as a city called `cityName`; see the module documentation for the passes and coordinates.
///
/// `footprintNoa`, when given, holds one attribute count per type (same order as `types`) that sizes its lot instead
/// of the type's own `noa`. The history replay passes each type's largest attribute count over the present and all
/// snapshots, so that every lot is big enough for the building at its largest; nothing else changes.
Layout layout(string cityName, const TypeDecl[] types, const(uint)[] footprintNoa = null)
{
    assert(footprintNoa is null || footprintNoa.length == types.length);
    auto root = buildTree(cityName, types, footprintNoa);
    measure(root);
    Layout result;
    // Start at the top-left corner that puts the whole city's centre on the origin.
    place(root, -root.w / 2, -root.d / 2, 0, result);
    result.width = root.w;
    result.depth = root.d;
    return result;
}

/// Builds the district tree. With more than one build module, the first level is one district per module, so that
/// packages with the same name in different modules stay apart. Below that, each package segment is one level:
/// `com.acme.shop` becomes `com → acme → shop`, and the type is added to the last one.
private Node buildTree(string cityName, const TypeDecl[] types, const(uint)[] footprintNoa)
{
    bool[string] modules;
    foreach (ref t; types) modules[t.module_] = true;
    const multiModule = modules.length > 1;
    auto root = new Node(cityName, "");
    foreach (i, ref t; types)
    {
        auto node = root;
        if (multiModule)
        {
            const module_ = t.module_.length ? t.module_ : "(root)";
            // The "module:" prefix keeps a module apart from a top-level package of the same name.
            const key = "module:" ~ module_;
            if (key !in node.children) node.children[key] = new Node(module_, key);
            node = node.children[key];
            node.module_ = true;
        }
        auto segments = t.packageSegments;
        // Types outside any package still need a district to stand in.
        if (segments.length == 0) segments = [t.language == Language.java ? "(default)" : "(root)"];
        // Ids look like "<module>/<package>", with the package written the way its language writes it:
        // com.acme.shop (Java), src/shop (TypeScript) or crate::shop (Rust).
        const sep = t.language.separator;
        const path = node.id.length ? node.id ~ "/" : "";
        string fqn;
        foreach (segment; segments)
        {
            fqn = fqn.length ? fqn ~ sep ~ segment : segment;
            if (segment !in node.children) node.children[segment] = new Node(segment, path ~ fqn);
            node = node.children[segment];
            node.separator = sep;
        }
        // `t` is a ref into the caller's array, so this is a pointer to the original declaration, not a copy.
        node.types ~= &t;
        node.sides ~= footprintNoa is null ? side(t) : side(footprintNoa[i]);
    }
    collapse(root);
    return root;
}

/// `com → acme → shop` with nothing in between becomes a single `com.acme.shop` district.
///
/// Without this, a typical Java project would be a tower of empty platforms, one per package segment, before the
/// first building appears. A district is merged with its only child when it has no types of its own. Modules are
/// never merged, neither into their parent nor with their child, so every module keeps its own platform.
///
/// The merged district takes the child's id, because that is the package that actually holds the code.
private void collapse(Node node)
{
    // Take the children out and rebuild the map: entries are re-keyed by their (possibly new) label. Assigning null
    // only drops this node's reference; a merged node may share the old map with the node it was folded from.
    auto children = node.sortedChildren;
    node.children = null;
    foreach (child; children)
    {
        auto c = child;
        // Keep folding while c is an empty pass-through: com → acme → shop → (types) ends as "com.acme.shop".
        while (!c.module_ && c.types.length == 0 && c.children.length == 1)
        {
            auto only = c.children.byValue.front;
            if (only.module_) break;
            auto joined = new Node(c.label ~ only.separator ~ only.label, only.id);
            joined.separator = only.separator;
            joined.children = only.children;
            joined.types = only.types;
            joined.sides = only.sides;
            c = joined;
        }
        // The chain's last district may still have chains of its own further down.
        collapse(c);
        // Re-key by the new label (e.g. "com.acme.shop") so districts stay sorted by what the viewer shows.
        node.children[c.label] = c;
    }
}

/// Sizes every district bottom-up: a district's size depends on its children's, so they are measured first. Both
/// sub-districts and buildings go into one packing, which is why buildings and sub-districts mix freely on a
/// platform.
private void measure(Node node)
{
    Item[] items;
    foreach (i, child; node.sortedChildren)
    {
        measure(child);
        items ~= Item(child.w + gap, child.d + gap, 0, 0, true, i);
    }
    foreach (i, s; node.sides) items ~= Item(s + gap, s + gap, 0, 0, false, i);
    double w, d;
    pack(items, w, d);
    node.items = items;
    // Drop the street after the last lot in each direction and add the kerb on both sides. An empty district
    // (size 0) must not go negative, hence the max.
    node.w = max(w - gap, 0.0) + 2 * pad;
    node.d = max(d - gap, 0.0) + 2 * pad;
}

/// Turns the relative positions from `measure` into absolute ones, top-down. `left`/`top` is where this district's
/// top-left corner ends up.
///
/// Recording the district before recursing gives `result.districts` its pre-order, and `index` is this district's
/// position in it.
private void place(Node node, double left, double top, uint depth, ref Layout result)
{
    const index = result.districts.length;
    result.districts ~= District(node.id, node.label, depth, left + node.w / 2, top + node.d / 2, node.w, node.d, node.typeCount);
    auto children = node.sortedChildren;
    foreach (item; node.items)
    {
        // The packer worked inside the kerb, so shift by pad as well as by the district's corner.
        const x = left + pad + item.x;
        const z = top + pad + item.z;
        if (item.isChild) place(children[item.index], x, z, depth + 1, result);
        else
        {
            // The item is the footprint plus the street; the building sits in its top-left corner, and the lot
            // stores the footprint's centre. It stands on this district's platform, one level above depth.
            // With a larger lot (history replay), the building keeps its own side, centred in the lot, as in the
            // Java and Rust editions.
            auto t = node.types[item.index];
            const s = node.sides[item.index];
            result.lots[t.fqn] = Lot(x + s / 2, z + s / 2, side(*t), (depth + 1) * platform, index);
        }
    }
}

/// Packs items into a compact rectangle; assigns item positions and returns (width, depth) via the out parameters.
///
/// The skyline packer below needs a fixed strip width, but the best width is not known in advance. The strip must
/// be at least as wide as the widest item. If it is much narrower than `√area`, the result is a tall column; if it
/// is much wider, a flat row. So this tries six widths around `√area` (the side of a square that would hold every
/// item without any waste) and keeps the best result.
///
/// "Best" means small and square. The score is the bounding box's area, with a penalty that grows with how far the
/// aspect ratio is from 1:1. `|ln(w/d)|` treats 2:1 and 1:2 alike and is 0 for a square, so a 2:1 rectangle costs
/// about 24% more than a square of the same area.
///
/// Note that the sort reorders `items` in place; positions are written back in that sorted order. `Item.index`
/// still identifies each item, which is why `place` does not depend on the order.
private void pack(Item[] items, out double width, out double depth)
{
    if (items.length == 0)
    {
        width = depth = 0;
        return;
    }
    // Largest first: by longest side, then by area, both descending. Small items placed last can fill the low spots
    // the big ones leave in the skyline; placed the other way round, big items land on a ragged skyline and waste
    // space. The stable sort keeps equal items in insertion order, like Java's List.sort with a reversed comparator.
    items.sort!((a, b) {
        const ma = max(a.w, a.d), mb = max(b.w, b.d);
        if (ma != mb) return ma > mb;
        return a.w * a.d > b.w * b.d;
    }, SwapStrategy.stable);
    double area = 0, maxW = 0;
    foreach (i; items)
    {
        area += i.w * i.d;
        maxW = max(maxW, i.w);
    }
    // Each attempt writes its positions into xs/zs; the best attempt's positions are copied aside.
    auto xs = new double[items.length], zs = new double[items.length];
    double[] bestX, bestZ;
    double bestW = 0, bestD = 0, bestScore = double.max;
    // Most factors are above 1: the packer always leaves some gaps, so the result needs more room than the bare item
    // area, and a slightly wider strip is more likely to come out square.
    foreach (f; [0.85, 1.0, 1.12, 1.25, 1.45, 1.7])
    {
        const strip = max(maxW, sqrt(area) * f);
        double w, d;
        skyline(items, strip, xs, zs, w, d);
        const score = w * d * (1 + 0.35 * abs(log(w / d)));
        // Strictly less: on a tie the earlier (narrower) width wins, which keeps the result deterministic.
        if (score < bestScore)
        {
            bestScore = score;
            bestX = xs.dup;
            bestZ = zs.dup;
            bestW = w;
            bestD = d;
        }
    }
    foreach (i, ref item; items)
    {
        item.x = bestX[i];
        item.z = bestZ[i];
    }
    width = bestW;
    depth = bestD;
}

/// Bottom-left skyline packing into a strip of the given width. Returns the bounding box (usedW, usedD) of what was
/// placed via the out parameters.
///
/// Picture the strip as a box `width` wide and infinitely deep, filled from the back (`z = 0`) forwards, like
/// stacking boxes against a wall. Only the front edge of what has been placed so far matters: the skyline. It is
/// stored as a list of segments, ordered by `x`, that cover `[0, width]` without gaps or overlaps. Each segment is
/// `[x, width, height]`, where height is how far forward (`z`) things already reach over that stretch.
///
/// ---
///   x →  0          4      6        10
///  z  ┌──────────┬──────┬──────────┐
///  ↓  │  item A  │  B   │  item C  │
///     │          ├──────┘          │     skyline: [0,4,3] [4,2,1] [6,4,3]
///     └──────────┘      └──────────┘
/// ---
///
/// Each item, in order, goes where its front edge (`z + depth`) ends up furthest back. Only the left ends of
/// segments are tried as positions: an item starting there rests on the highest segment it spans. On ties the
/// leftmost position wins. Positions are written to `xs`/`zs`, indexed like `items`.
///
/// Items never go under an overhang: the region behind the skyline is treated as full even where it has holes.
/// That wastes a little space, but keeps the algorithm simple and fast (O(n · segments) per item).
private void skyline(const Item[] items, double width, double[] xs, double[] zs, out double usedW, out double usedD)
{
    // Coordinates are sums of doubles, so compare with a tolerance; otherwise a rounding error could make an item
    // that exactly fills a gap look too big for it.
    enum eps = 1e-9;
    // Each segment: [x, width, height]. Initially the floor of the whole strip, at height 0.
    double[3][] sky = [[0.0, width, 0.0]];
    // Bounding box of everything placed so far.
    usedW = 0;
    usedD = 0;
    foreach (n, it; items)
    {
        // Unlike Java's -1 sentinel, bestIdx starts at 0; it is always overwritten because segment 0 always fits
        // (the strip is at least as wide as the widest item).
        size_t bestIdx = 0;
        double bestTop = double.max, bestY = 0;
        // Try the item's left edge at the start of each segment.
        foreach (i; 0 .. sky.length)
        {
            const x = sky[i][0];
            // Segments are ordered by x, so once the item sticks out on the right it does so for every later
            // segment too.
            if (x + it.w > width + eps) break;
            // The item rests on the highest of the segments under it: walk right until its width is covered.
            double y = 0, remaining = it.w;
            for (size_t j = i; j < sky.length && remaining > eps; j++)
            {
                y = max(y, sky[j][2]);
                remaining -= sky[j][1];
            }
            // Keep the position whose front edge is furthest back. Strictly less (by more than eps) means a later
            // position must be really better, so ties go to the leftmost.
            if (y + it.d < bestTop - eps)
            {
                bestTop = y + it.d;
                bestIdx = i;
                bestY = y;
            }
        }
        const x = sky[bestIdx][0];
        xs[n] = x;
        zs[n] = bestY;
        usedW = max(usedW, x + it.w);
        usedD = max(usedD, bestY + it.d);

        // Update the skyline. The item adds a new segment covering [x, end) at its front edge. It goes in before
        // segment bestIdx (which starts at x too); then the segments it now covers are removed or shortened.
        // k is const because removing segment k shifts the next one into its place.
        const end = x + it.w;
        double[3] seg = [x, it.w, bestY + it.d];
        sky = sky[0 .. bestIdx] ~ seg ~ sky[bestIdx .. $];
        const k = bestIdx + 1;
        while (k < sky.length)
        {
            const segEnd = sky[k][0] + sky[k][1];
            // Starts at or after the item's right edge: nothing more to cover.
            if (sky[k][0] >= end - eps) break;
            // Completely under the item: gone. Otherwise it sticks out on the right: keep only that part.
            if (segEnd <= end + eps) sky = sky[0 .. k] ~ sky[k + 1 .. $];
            else
            {
                sky[k][1] = segEnd - end;
                sky[k][0] = end;
                break;
            }
        }
        // Merge neighbours of equal height. Fewer segments make later searches faster, and a wide flat segment lets
        // a wide item start at its left end. Walking backwards keeps indices valid while removing; sky is never
        // empty, so sky.length - 1 cannot underflow.
        for (size_t i = sky.length - 1; i > 0; i--)
        {
            if (abs(sky[i - 1][2] - sky[i][2]) < eps)
            {
                sky[i - 1][1] += sky[i][1];
                sky = sky[0 .. i] ~ sky[i + 1 .. $];
            }
        }
    }
}

// Layout rules, one per unittest. The Java (`CityLayoutTest`) and Rust (`layout.rs`) editions run the same scenarios
// on the same synthetic fixtures with the same expectations; each block names the Java test it mirrors.
version (unittest)
{
    import std.conv : to;

    /// A type of the given language in package `pkg` and build module `module_`.
    private TypeDecl tyIn(Language lang, string module_, string pkg, string name, uint noa)
    {
        TypeDecl t;
        t.language = lang;
        t.fqn = pkg.length ? pkg ~ lang.separator ~ name : name;
        t.name = name;
        t.pkg = pkg;
        t.module_ = module_;
        t.kind = "class";
        t.noa = noa;
        return t;
    }

    private TypeDecl ty(string pkg, string name, uint noa) { return tyIn(Language.java, "", pkg, name, noa); }

    /// The shared fixture of all three editions: 300 Java types spread evenly over six packages (two of them only
    /// reachable through empty chains), mostly small, every fifth one larger.
    private TypeDecl[] city()
    {
        immutable pkgs = ["a", "a.b", "a.b.c", "a.d", "e", "e.f.g"];
        TypeDecl[] types;
        foreach (i; 0 .. 300)
            types ~= ty(pkgs[(i * 7) % pkgs.length], "T" ~ i.to!string, ((i * 13) % 9) * (i % 5 == 0 ? 9 : 1));
        return types;
    }

    private string[] labels(ref Layout l)
    {
        string[] r;
        foreach (d; l.districts) r ~= d.label;
        return r;
    }

    private string[] ids(ref Layout l)
    {
        string[] r;
        foreach (d; l.districts) r ~= d.id;
        return r;
    }

    private uint[] depths(ref Layout l)
    {
        uint[] r;
        foreach (d; l.districts) r ~= d.depth;
        return r;
    }

    /// Rounds to 9 decimals, so exact expectations are not upset by floating-point noise.
    private double round9(double v)
    {
        import std.math : round;
        return round(v * 1e9) / 1e9;
    }

    /// The parent of every district: the closest earlier district one level up (they are in pre-order).
    private size_t[] parents(const District[] ds)
    {
        auto parent = new size_t[ds.length];
        parent[0] = size_t.max;
        foreach (i; 1 .. ds.length)
        {
            size_t p = i - 1;
            while (ds[p].depth + 1 != ds[i].depth) p--;
            parent[i] = p;
        }
        return parent;
    }

    /// The lots as "x z side district" strings, sorted: the set of places regardless of which type got which.
    private string[] sortedLots(ref Layout l)
    {
        import std.format : format;
        string[] r;
        foreach (a; l.lots) r ~= format("%.17g %.17g %.17g %s", a.x, a.z, a.side, a.district);
        r.sort();
        return r;
    }
}

unittest // everyTypeGetsALot: every type of the input gets exactly one lot, keyed by its fqn
{
    auto types = city();
    auto l = layout("city", types);
    assert(l.lots.length == types.length);
    foreach (ref t; types) assert(t.fqn in l.lots, t.fqn);
}

unittest // buildingsInTheSameDistrictDoNotOverlap: any two lots are at least a street (gap) apart along x or z
{
    auto l = layout("city", city());
    auto lots = l.lots.values;
    foreach (i, a; lots)
        foreach (b; lots[i + 1 .. $])
        {
            const gx = abs(a.x - b.x) - (a.side + b.side) / 2;
            const gz = abs(a.z - b.z) - (a.side + b.side) / 2;
            assert(gx >= gap - 1e-6 || gz >= gap - 1e-6);
        }
}

unittest // buildingsStandInsideTheirDistrict: at least the kerb (pad) to every edge, y = (depth + 1) * platform
{
    auto l = layout("city", city());
    const p = pad - 1e-6;
    foreach (a; l.lots)
    {
        const d = l.districts[a.district];
        assert(a.x - a.side / 2 >= d.x - d.w / 2 + p && a.x + a.side / 2 <= d.x + d.w / 2 - p);
        assert(a.z - a.side / 2 >= d.z - d.d / 2 + p && a.z + a.side / 2 <= d.z + d.d / 2 - p);
        assert(abs(a.y - (d.depth + 1) * platform) < 1e-9);
    }
}

unittest // subDistrictsStandInsideTheirParentWithStreetsBetween: kerb to the parent's edges, streets between siblings
{
    auto l = layout("city", city());
    const ds = l.districts;
    const parent = parents(ds);
    const p = pad - 1e-6;
    foreach (i; 1 .. ds.length)
    {
        const c = ds[i], q = ds[parent[i]];
        assert(c.x - c.w / 2 >= q.x - q.w / 2 + p && c.x + c.w / 2 <= q.x + q.w / 2 - p, c.label);
        assert(c.z - c.d / 2 >= q.z - q.d / 2 + p && c.z + c.d / 2 <= q.z + q.d / 2 - p, c.label);
        foreach (j; i + 1 .. ds.length)
            if (parent[j] == parent[i])
            {
                const s = ds[j];
                const gx = abs(c.x - s.x) - (c.w + s.w) / 2;
                const gz = abs(c.z - s.z) - (c.d + s.d) / 2;
                assert(gx >= gap - 1e-6 || gz >= gap - 1e-6, c.label ~ " and " ~ s.label ~ " overlap");
            }
    }
}

unittest // singleChildPackageChainsAreCollapsed: an empty chain above the only package becomes one district
{
    auto l = layout("city", [ty("com.acme.shop", "Cart", 2)]);
    assert(labels(l) == ["city", "com.acme.shop"]);
    assert(ids(l) == ["", "com.acme.shop"]);
}

unittest // cityIsRoughlySquare: a packed district comes out roughly square, not as a long row or column
{
    TypeDecl[] types;
    // A fixed permutation of 0..399 (37 is coprime with 400), so the input is not in size order.
    foreach (i; 0 .. 400)
    {
        const k = i * 37 % 400;
        types ~= ty("p", "T" ~ k.to!string, k % 7);
    }
    auto l = layout("city", types);
    const aspect = l.width / l.depth;
    assert(aspect >= 0.5 && aspect <= 2);
}

unittest // layoutIsDeterministic: same input, same city; another order, same districts and the same set of lots
{
    import std.algorithm : reverse;
    auto a = layout("city", city());
    auto again = layout("city", city());
    assert(a.districts == again.districts);
    assert(a.lots == again.lots);
    // Only equally sized buildings may swap places: the packer keeps their input order (the analysis always hands
    // the types over sorted by fqn).
    auto reversed = city();
    reversed.reverse();
    auto b = layout("city", reversed);
    assert(a.districts == b.districts);
    assert(a.width == b.width && a.depth == b.depth);
    assert(sortedLots(a) == sortedLots(b));
}

unittest // footprintGrowsWithTheSquareRootOfTheAttributes: side = 1.2 + 0.8 * sqrt(noa), 1.2 without attributes
{
    assert(abs(side(0) - 1.2) < 1e-12 && abs(side(1) - 2.0) < 1e-12);
    assert(abs(side(4) - 2.8) < 1e-12 && abs(side(9) - 3.6) < 1e-12);
    auto t = ty("p", "A", 9);
    assert(abs(side(t) - side(9)) < 1e-12);
}

unittest // emptyCityIsOneBareDistrict: just the kerb on both sides, centred on the origin
{
    auto l = layout("city", []);
    assert(l.districts == [District("", "city", 0, 0, 0, 2 * pad, 2 * pad, 0)]);
    assert(l.lots.length == 0);
    assert(l.width == 2 * pad && l.depth == 2 * pad);
}

unittest // districtsArePreOrderWithDepthsIdsAndSubtreeTypeCounts: siblings in packing order, larger first
{
    auto l = layout("city", city());
    assert(labels(l) == ["city", "a", "b", "c", "d", "e", "f.g"]);
    assert(ids(l) == ["", "a", "a.b", "a.b.c", "a.d", "e", "e.f.g"]);
    assert(depths(l) == [0, 1, 2, 3, 2, 1, 2]);
    uint[] types;
    foreach (d; l.districts) types ~= d.types;
    assert(types == [300, 200, 100, 50, 50, 100, 50]);
}

unittest // collapsedChainsJoinWithTheirLanguageSeparator: "/" for TypeScript, "::" for Rust
{
    auto ts = layout("city", [tyIn(Language.typescript, "", "src/ui/forms", "F", 0)]);
    assert(labels(ts) == ["city", "src/ui/forms"] && ids(ts) == ["", "src/ui/forms"]);
    auto rust = layout("city", [tyIn(Language.rust, "", "net::http", "Get", 0)]);
    assert(labels(rust) == ["city", "net::http"] && ids(rust) == ["", "net::http"]);
}

unittest // districtWithOwnTypesIsNotFolded: a package with types of its own keeps its own district
{
    auto l = layout("city", [ty("a", "A", 0), ty("a.b", "B", 0)]);
    assert(labels(l) == ["city", "a", "b"] && ids(l) == ["", "a", "a.b"]);
    assert(l.lots["a.A"].district == 1 && l.lots["a.b.B"].district == 2);
}

unittest // modulesGetTheirOwnDistrictAndAreNeverFolded: "(root)" for the root module, ids start with "module:"
{
    auto l = layout("city", [tyIn(Language.java, "core", "x.y", "X", 0), tyIn(Language.java, "", "z", "Z", 0)]);
    assert(labels(l) == ["city", "(root)", "z", "core", "x.y"]);
    assert(ids(l) == ["", "module:(root)", "module:(root)/z", "module:core", "module:core/x.y"]);
    assert(depths(l) == [0, 1, 2, 1, 2]);
    assert(l.lots["x.y.X"].district == 4);
}

unittest // singleModuleHasNoModuleLevel: the packages sit directly on the city
{
    auto l = layout("city", [tyIn(Language.java, "core", "x.y", "X", 0), tyIn(Language.java, "core", "x.w", "W", 0)]);
    assert(labels(l) == ["city", "x", "w", "y"] && ids(l) == ["", "x", "x.w", "x.y"]);
}

unittest // typesOutsideAPackageStandInADefaultDistrict: "(default)" for Java, "(root)" for TypeScript and Rust
{
    auto l = layout("city", [tyIn(Language.java, "", "", "J", 0), tyIn(Language.typescript, "", "", "T", 0),
        tyIn(Language.rust, "", "", "R", 0)]);
    // "(root)" holds two buildings, so it is packed (and listed) first.
    assert(labels(l) == ["city", "(root)", "(default)"] && ids(l) == ["", "(root)", "(default)"]);
    assert(l.lots["J"].district == 2 && l.lots["T"].district == 1 && l.lots["R"].district == 1);
}

unittest // largerLotsCentreTheBuildingsOwnFootprint: placed as if larger, each building keeps its own side
{
    auto own = [ty("p", "A", 0), ty("p", "B", 1), ty("q", "C", 4)];
    auto grown = [ty("p", "A", 9), ty("p", "B", 4), ty("q", "C", 4)];
    auto wide = layout("city", own, [9u, 4, 4]);
    auto reference = layout("city", grown);
    assert(wide.districts == reference.districts);
    foreach (ref t; own)
    {
        const w = wide.lots[t.fqn], g = reference.lots[t.fqn];
        assert(abs(w.x - g.x) < 1e-9 && abs(w.z - g.z) < 1e-9, t.fqn);
        assert(abs(w.side - side(t)) < 1e-12, t.fqn);
        assert(w.district == g.district);
    }
}

unittest // twoBuildingsHaveExactCoordinates: pins the packer on a tiny case, centred on the origin
{
    // The bigger building goes first at the back-left corner, the smaller one in front of it (a wider strip would
    // put it beside, but the score ties and the narrower strip wins).
    auto l = layout("city", [ty("p", "A", 0), ty("p", "B", 9)]);
    assert(round9(l.width) == 7.6 && round9(l.depth) == 9.6);
    const p = l.districts[1];
    assert([round9(p.x), round9(p.z), round9(p.w), round9(p.d)] == [0.0, 0.0, 5.6, 7.6]);
    const b = l.lots["p.B"], a = l.lots["p.A"];
    assert([round9(b.x), round9(b.z), round9(b.side), round9(b.y)] == [0.0, -1.0, 3.6, 0.8]);
    assert([round9(a.x), round9(a.z), round9(a.side), round9(a.y)] == [-1.2, 2.2, 1.2, 0.8]);
}
