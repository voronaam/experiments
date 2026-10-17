/// Lays the city out: packages become nested, stacked districts and types become buildings whose footprint grows
/// with their number of attributes. Each district's contents are packed with a skyline bin-packer, trying several
/// strip widths and keeping the most compact, square-ish result. (A faithful port of the Java/Rust editions.)
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
    size_t district;
}

struct Layout
{
    District[] districts;
    Lot[string] lots;
    double width, depth;
}

double side(ref const TypeDecl t) pure nothrow @safe
{
    return 1.2 + 0.8 * sqrt(cast(double) t.noa);
}

private final class Node
{
    string label, id;
    Node[string] children; // iterated in sorted key order
    const(TypeDecl)*[] types;
    bool module_;
    string separator = ".";
    double w = 0, d = 0;
    Item[] items;

    this(string label, string id)
    {
        this.label = label;
        this.id = id;
    }

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

/// A packed rectangle: a child district (index into sortedChildren) or a type (index into types).
private struct Item
{
    double w, d, x = 0, z = 0;
    bool isChild;
    size_t index;
}

Layout layout(string cityName, const TypeDecl[] types)
{
    auto root = buildTree(cityName, types);
    measure(root);
    Layout result;
    place(root, -root.w / 2, -root.d / 2, 0, result);
    result.width = root.w;
    result.depth = root.d;
    return result;
}

private Node buildTree(string cityName, const TypeDecl[] types)
{
    bool[string] modules;
    foreach (ref t; types) modules[t.module_] = true;
    const multiModule = modules.length > 1;
    auto root = new Node(cityName, "");
    foreach (ref t; types)
    {
        auto node = root;
        if (multiModule)
        {
            const module_ = t.module_.length ? t.module_ : "(root)";
            const key = "module:" ~ module_;
            if (key !in node.children) node.children[key] = new Node(module_, key);
            node = node.children[key];
            node.module_ = true;
        }
        auto segments = t.packageSegments;
        if (segments.length == 0) segments = [t.language == Language.java ? "(default)" : "(root)"];
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
        node.types ~= &t;
    }
    collapse(root);
    return root;
}

/// `com → acme → shop` with nothing in between becomes a single `com.acme.shop` district.
private void collapse(Node node)
{
    auto children = node.sortedChildren;
    node.children = null;
    foreach (child; children)
    {
        auto c = child;
        while (!c.module_ && c.types.length == 0 && c.children.length == 1)
        {
            auto only = c.children.byValue.front;
            if (only.module_) break;
            auto joined = new Node(c.label ~ only.separator ~ only.label, only.id);
            joined.separator = only.separator;
            joined.children = only.children;
            joined.types = only.types;
            c = joined;
        }
        collapse(c);
        node.children[c.label] = c;
    }
}

private void measure(Node node)
{
    Item[] items;
    foreach (i, child; node.sortedChildren)
    {
        measure(child);
        items ~= Item(child.w + gap, child.d + gap, 0, 0, true, i);
    }
    foreach (i, t; node.types)
    {
        const s = side(*t);
        items ~= Item(s + gap, s + gap, 0, 0, false, i);
    }
    double w, d;
    pack(items, w, d);
    node.items = items;
    node.w = max(w - gap, 0.0) + 2 * pad;
    node.d = max(d - gap, 0.0) + 2 * pad;
}

private void place(Node node, double left, double top, uint depth, ref Layout result)
{
    const index = result.districts.length;
    result.districts ~= District(node.id, node.label, depth, left + node.w / 2, top + node.d / 2, node.w, node.d, node.typeCount);
    auto children = node.sortedChildren;
    foreach (item; node.items)
    {
        const x = left + pad + item.x;
        const z = top + pad + item.z;
        if (item.isChild) place(children[item.index], x, z, depth + 1, result);
        else
        {
            auto t = node.types[item.index];
            const s = side(*t);
            result.lots[t.fqn] = Lot(x + s / 2, z + s / 2, s, (depth + 1) * platform, index);
        }
    }
}

/// Packs items into a compact rectangle; assigns item positions and returns (width, depth).
private void pack(Item[] items, out double width, out double depth)
{
    if (items.length == 0)
    {
        width = depth = 0;
        return;
    }
    // largest first (stable, like Java's reversed comparator)
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
    auto xs = new double[items.length], zs = new double[items.length];
    double[] bestX, bestZ;
    double bestW = 0, bestD = 0, bestScore = double.max;
    foreach (f; [0.85, 1.0, 1.12, 1.25, 1.45, 1.7])
    {
        const strip = max(maxW, sqrt(area) * f);
        double w, d;
        skyline(items, strip, xs, zs, w, d);
        const score = w * d * (1 + 0.35 * abs(log(w / d)));
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

/// Bottom-left skyline packing into a strip of the given width.
private void skyline(const Item[] items, double width, double[] xs, double[] zs, out double usedW, out double usedD)
{
    enum eps = 1e-9;
    // each segment: [x, width, height]
    double[3][] sky = [[0.0, width, 0.0]];
    usedW = 0;
    usedD = 0;
    foreach (n, it; items)
    {
        size_t bestIdx = 0;
        double bestTop = double.max, bestY = 0;
        foreach (i; 0 .. sky.length)
        {
            const x = sky[i][0];
            if (x + it.w > width + eps) break;
            double y = 0, remaining = it.w;
            for (size_t j = i; j < sky.length && remaining > eps; j++)
            {
                y = max(y, sky[j][2]);
                remaining -= sky[j][1];
            }
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

        const end = x + it.w;
        double[3] seg = [x, it.w, bestY + it.d];
        sky = sky[0 .. bestIdx] ~ seg ~ sky[bestIdx .. $];
        const k = bestIdx + 1;
        while (k < sky.length)
        {
            const segEnd = sky[k][0] + sky[k][1];
            if (sky[k][0] >= end - eps) break;
            if (segEnd <= end + eps) sky = sky[0 .. k] ~ sky[k + 1 .. $];
            else
            {
                sky[k][1] = segEnd - end;
                sky[k][0] = end;
                break;
            }
        }
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

unittest
{
    TypeDecl ty(string pkg, string name, uint noa)
    {
        TypeDecl t;
        t.language = Language.java;
        t.fqn = pkg ~ "." ~ name;
        t.name = name;
        t.pkg = pkg;
        t.kind = "class";
        t.noa = noa;
        return t;
    }

    import std.conv : to;
    immutable pkgs = ["a", "a.b", "a.b.c", "a.d", "e", "e.f.g"];
    TypeDecl[] types;
    foreach (i; 0 .. 300)
        types ~= ty(pkgs[(i * 7) % pkgs.length], "T" ~ i.to!string, ((i * 13) % 9) * (i % 5 == 0 ? 9 : 1));
    auto l = layout("city", types);
    assert(l.lots.length == types.length);
    auto lots = l.lots.values;
    foreach (i, a; lots)
    {
        const dd = l.districts[a.district];
        assert(a.x - a.side / 2 >= dd.x - dd.w / 2 - 1e-6 && a.x + a.side / 2 <= dd.x + dd.w / 2 + 1e-6);
        foreach (b; lots[i + 1 .. $])
        {
            const gx = abs(a.x - b.x) - (a.side + b.side) / 2;
            const gz = abs(a.z - b.z) - (a.side + b.side) / 2;
            assert(gx >= gap - 1e-6 || gz >= gap - 1e-6);
        }
    }
    auto single = layout("city", [ty("com.acme.shop", "Cart", 2)]);
    assert(single.districts.length == 2 && single.districts[1].label == "com.acme.shop");
}
