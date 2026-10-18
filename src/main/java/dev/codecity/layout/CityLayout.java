package dev.codecity.layout;

import dev.codecity.analysis.TypeInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

/**
 * Lays the city out: packages become nested, stacked districts and types become buildings whose footprint grows with
 * their number of attributes. Each district's contents are packed with a skyline bin-packer, trying several strip
 * widths and keeping the most compact, square-ish result.
 *
 * <h2>Coordinates</h2>
 * The city lies on the ground plane: {@code x} runs left to right and {@code z} runs from the back to the front. A
 * city of width {@code w} and depth {@code d} is centred on the origin. {@code y} is up and only matters for the
 * platforms: a district at nesting depth {@code n} is the {@code n}-th platform of the stack, and its buildings stand
 * on top of it at {@code (n + 1) * PLATFORM}.
 *
 * <h2>The three passes</h2>
 * <ol>
 *   <li>{@link #buildTree} turns the flat list of types into a tree of districts (modules → packages → types) and
 *       collapses chains of empty packages.</li>
 *   <li>{@link #measure} works bottom-up. A district can only be packed once the size of every child district is
 *       known, so children are measured first. Each district then packs its children and its own buildings as plain
 *       rectangles and so learns its own size. Positions are relative to the district's top-left corner.</li>
 *   <li>{@link #place} works top-down, turning those relative positions into absolute ones and emitting the
 *       districts and building lots.</li>
 * </ol>
 *
 * <h2>Streets</h2>
 * Every rectangle handed to the packer is {@link #GAP} wider and deeper than the thing it holds. Packing those
 * rectangles edge to edge leaves a {@code GAP}-wide street to the right of and in front of every lot, so the packer
 * itself never has to think about spacing. The last street along each edge is not needed, so {@link #measure}
 * subtracts one {@code GAP} again and adds {@link #PAD} on every side for the kerb.
 *
 * <p>The layout is deterministic: districts are kept in sorted maps and the sort in {@link #pack} is stable, so the
 * same input always yields the same city. The Rust and D editions implement exactly the same algorithm and produce
 * the same coordinates.
 */
public final class CityLayout {

    /** Thickness of one district platform. */
    public static final double PLATFORM = 0.4;
    /** Street width between neighbouring lots. */
    public static final double GAP = 0.8;
    /** Kerb between a district's edge and its contents. */
    public static final double PAD = 1.0;

    /**
     * One platform. {@code x}/{@code z} is its centre, {@code w}/{@code d} its size, {@code depth} its nesting level
     * (0 is the city itself) and {@code types} the number of types in it and all its sub-districts.
     */
    public record District(String id, String label, int depth, double x, double z, double w, double d, int types) {
    }

    /** Where a building stands: footprint centre, side length and the height of the ground it stands on. */
    public record Lot(double x, double z, double side, double y, int districtIndex) {
    }

    /**
     * The laid-out city. {@code districts} is in pre-order (every district comes before its sub-districts, the city
     * first), which is also the order they are drawn in; {@link Lot#districtIndex} points into it. {@code lots} is
     * keyed by the type's fully qualified name.
     */
    public record Result(List<District> districts, Map<String, Lot> lots, double width, double depth) {
    }

    /**
     * Side length of a building's square footprint. It grows with the square root of the attribute count, so the
     * footprint area grows roughly linearly with it, and even a type without attributes gets a visible 1.2 × 1.2 lot.
     */
    public static double side(TypeInfo t) {
        return side(t.noa());
    }

    /** {@link #side(TypeInfo)} for an attribute count; the history replay sizes footprints with it. */
    public static double side(int noa) {
        return 1.2 + 0.8 * Math.sqrt(noa);
    }

    /** A district while the layout is being computed. */
    private static final class Node {
        /** Name shown in the viewer, e.g. {@code shop} or, after collapsing, {@code com.acme.shop}. */
        final String label;
        /** Stable identifier: the module path plus the fully qualified package name ("" for the city). */
        final String id;
        /** Sub-districts by label. A sorted map keeps the layout independent of the order types were found in. */
        final Map<String, Node> children = new TreeMap<>();
        /** Types declared directly in this district (not in its sub-districts). */
        final List<TypeInfo> types = new ArrayList<>();
        /** A build module (Maven/Gradle module, npm project, crate). Modules are never merged into their parent. */
        boolean module;
        /** How this district's name joins with a collapsed child's: ".", "/" or "::". */
        String separator = ".";
        /** Size of the platform including its kerb; set by {@link #measure}. */
        double w;
        double d;
        /** The packed contents, with positions relative to the inside of the kerb; set by {@link #measure}. */
        List<Item> items = List.of();

        Node(String label, String id) {
            this.label = label;
            this.id = id;
        }

        int typeCount() {
            return types.size() + children.values().stream().mapToInt(Node::typeCount).sum();
        }
    }

    /**
     * A rectangle for the packer: either a sub-district ({@code node}) or a building ({@code type}); the other one is
     * null. {@code w}/{@code d} include the street ({@link #GAP}); {@code x}/{@code z} is the top-left corner the packer
     * chose.
     */
    static final class Item {
        final double w;
        final double d;
        double x;
        double z;
        final Node node;
        final TypeInfo type;

        Item(double w, double d, Node node, TypeInfo type) {
            this.w = w;
            this.d = d;
            this.node = node;
            this.type = type;
        }
    }

    private CityLayout() {
    }

    public static Result layout(String cityName, List<TypeInfo> types) {
        return layout(cityName, types, TypeInfo::noa);
    }

    /**
     * Lays the city out with each lot sized for {@code noa(type)} attributes instead of the type's own count. The
     * history replay passes the largest count the type ever had, so that every lot is big enough for the building at
     * its widest. A {@link Lot} still has the type's own {@link #side(TypeInfo)}, centred in the larger lot; everything
     * else is the same as {@link #layout(String, List)}.
     */
    public static Result layout(String cityName, List<TypeInfo> types, ToIntFunction<TypeInfo> noa) {
        Node root = buildTree(cityName, types);
        measure(root, noa);
        List<District> districts = new ArrayList<>();
        Map<String, Lot> lots = new HashMap<>();
        // Start at the top-left corner that puts the whole city's centre on the origin.
        place(root, -root.w / 2, -root.d / 2, 0, districts, lots, noa);
        return new Result(districts, lots, root.w, root.d);
    }

    /**
     * Builds the district tree. With more than one build module, the first level is one district per module, so that
     * packages with the same name in different modules stay apart. Below that, each package segment is one level:
     * {@code com.acme.shop} becomes {@code com → acme → shop}, and the type is added to the last one.
     */
    private static Node buildTree(String cityName, List<TypeInfo> types) {
        boolean multiModule = types.stream().map(TypeInfo::module).distinct().count() > 1;
        Node root = new Node(cityName, "");
        for (TypeInfo t : types) {
            Node node = root;
            if (multiModule) {
                // The "module:" prefix keeps a module apart from a top-level package of the same name.
                String module = t.module().isEmpty() ? "(root)" : t.module();
                node = node.children.computeIfAbsent("module:" + module, k -> new Node(module, k));
                node.module = true;
            }
            List<String> segments = t.packageSegments();
            // Types outside any package still need a district to stand in.
            if (segments.isEmpty()) {
                segments = List.of(t.language() == dev.codecity.analysis.Language.JAVA ? "(default)" : "(root)");
            }
            // Ids look like "<module>/<package>", with the package written the way its language writes it:
            // com.acme.shop (Java), src/shop (TypeScript) or crate::shop (Rust).
            String sep = t.language().separator;
            String path = node.id.isEmpty() ? "" : node.id + "/";
            String fqn = "";
            for (String segment : segments) {
                fqn = fqn.isEmpty() ? segment : fqn + sep + segment;
                String id = path + fqn;
                node = node.children.computeIfAbsent(segment, k -> new Node(k, id));
                node.separator = sep;
            }
            node.types.add(t);
        }
        collapse(root);
        return root;
    }

    /**
     * {@code com → acme → shop} with nothing in between becomes a single {@code com.acme.shop} district.
     *
     * <p>Without this, a typical Java project would be a tower of empty platforms, one per package segment, before
     * the first building appears. A district is merged with its only child when it has no types of its own. Modules
     * are never merged, neither into their parent nor with their child, so every module keeps its own platform.
     *
     * <p>The merged district takes the child's id, because that is the package that actually holds the code.
     */
    private static void collapse(Node node) {
        Map<String, Node> merged = new TreeMap<>();
        for (Node child : node.children.values()) {
            Node c = child;
            // Keep folding while c is an empty pass-through: com → acme → shop → (types) ends as "com.acme.shop".
            while (!c.module && c.types.isEmpty() && c.children.size() == 1) {
                Node only = c.children.values().iterator().next();
                if (only.module) {
                    break;
                }
                Node joined = new Node(c.label + only.separator + only.label, only.id);
                joined.separator = only.separator;
                joined.children.putAll(only.children);
                joined.types.addAll(only.types);
                c = joined;
            }
            // The chain's last district may still have chains of its own further down.
            collapse(c);
            // Re-key by the new label (e.g. "com.acme.shop") so districts stay sorted by what the viewer shows.
            merged.put(c.label, c);
        }
        node.children.clear();
        node.children.putAll(merged);
    }

    /**
     * Sizes every district bottom-up: a district's size depends on its children's, so they are measured first. Both
     * sub-districts and buildings go into one packing, which is why buildings and sub-districts mix freely on a
     * platform.
     */
    private static void measure(Node node, ToIntFunction<TypeInfo> noa) {
        List<Item> items = new ArrayList<>();
        for (Node child : node.children.values()) {
            measure(child, noa);
            items.add(new Item(child.w + GAP, child.d + GAP, child, null));
        }
        for (TypeInfo t : node.types) {
            double s = side(noa.applyAsInt(t));
            items.add(new Item(s + GAP, s + GAP, null, t));
        }
        double[] size = pack(items);
        node.items = items;
        // Drop the street after the last lot in each direction and add the kerb on both sides. An empty district
        // (size 0) must not go negative, hence the max.
        node.w = Math.max(size[0] - GAP, 0) + 2 * PAD;
        node.d = Math.max(size[1] - GAP, 0) + 2 * PAD;
    }

    /**
     * Turns the relative positions from {@link #measure} into absolute ones, top-down. {@code left}/{@code top} is
     * where this district's top-left corner ends up.
     *
     * <p>Recording the district before recursing gives {@code districts} its pre-order, and {@code index} is this
     * district's position in it.
     */
    private static void place(Node node, double left, double top, int depth,
                              List<District> districts, Map<String, Lot> lots, ToIntFunction<TypeInfo> noa) {
        int index = districts.size();
        districts.add(new District(node.id, node.label, depth,
                left + node.w / 2, top + node.d / 2, node.w, node.d, node.typeCount()));
        for (Item item : node.items) {
            // The packer worked inside the kerb, so shift by PAD as well as by the district's corner.
            double x = left + PAD + item.x;
            double z = top + PAD + item.z;
            if (item.node != null) {
                place(item.node, x, z, depth + 1, districts, lots, noa);
            } else {
                // The item is the footprint plus the street; the building sits in its top-left corner, and the lot
                // stores the footprint's centre. It stands on this district's platform, one level above depth.
                // With a larger lot (history replay), the building keeps its own side, centred in the lot.
                double s = side(noa.applyAsInt(item.type));
                lots.put(item.type.fqn(), new Lot(x + s / 2, z + s / 2, side(item.type), (depth + 1) * PLATFORM,
                        index));
            }
        }
    }

    /**
     * Packs items into a compact rectangle; assigns item positions and returns {width, depth}.
     *
     * <p>The skyline packer below needs a fixed strip width, but the best width is not known in advance. The strip
     * must be at least as wide as the widest item. If it is much narrower than {@code √area}, the result is a tall
     * column; if it is much wider, a flat row. So this tries six widths around {@code √area} (the side of a square
     * that would hold every item without any waste) and keeps the best result.
     *
     * <p>"Best" means small and square. The score is the bounding box's area, with a penalty that grows with how far
     * the aspect ratio is from 1:1. {@code |ln(w/d)|} treats 2:1 and 1:2 alike and is 0 for a square, so a 2:1
     * rectangle costs about 24% more than a square of the same area.
     *
     * <p>Note that the sort changes the order of {@code items}; positions are written back in that sorted order.
     */
    static double[] pack(List<Item> items) {
        if (items.isEmpty()) {
            return new double[]{0, 0};
        }
        // Largest first: by longest side, then by area. Small items placed last can fill the low spots the big ones
        // leave in the skyline; placed the other way round, big items land on a ragged skyline and waste space.
        // reversed() applies to the whole comparator, so both keys are descending.
        items.sort(Comparator.<Item>comparingDouble(i -> Math.max(i.w, i.d))
                .thenComparingDouble(i -> i.w * i.d).reversed());
        double area = 0;
        double maxW = 0;
        for (Item i : items) {
            area += i.w * i.d;
            maxW = Math.max(maxW, i.w);
        }
        // Each attempt writes its positions into xs/zs; the best attempt's positions are copied aside.
        double[] xs = new double[items.size()];
        double[] zs = new double[items.size()];
        double[] bestX = null;
        double[] bestZ = null;
        double[] bestSize = null;
        double bestScore = Double.MAX_VALUE;
        // Most factors are above 1: the packer always leaves some gaps, so the result needs more room than the bare
        // item area, and a slightly wider strip is more likely to come out square.
        for (double f : new double[]{0.85, 1.0, 1.12, 1.25, 1.45, 1.7}) {
            double width = Math.max(maxW, Math.sqrt(area) * f);
            double[] size = skyline(items, width, xs, zs);
            double score = size[0] * size[1] * (1 + 0.35 * Math.abs(Math.log(size[0] / size[1])));
            // Strictly less: on a tie the earlier (narrower) width wins, which keeps the result deterministic.
            if (score < bestScore) {
                bestScore = score;
                bestX = xs.clone();
                bestZ = zs.clone();
                bestSize = size;
            }
        }
        for (int i = 0; i < items.size(); i++) {
            items.get(i).x = bestX[i];
            items.get(i).z = bestZ[i];
        }
        return bestSize;
    }

    /**
     * Bottom-left skyline packing into a strip of the given width. Returns {usedWidth, depth}.
     *
     * <p>Picture the strip as a box {@code width} wide and infinitely deep, filled from the back ({@code z = 0})
     * forwards, like stacking boxes against a wall. Only the front edge of what has been placed so far matters: the
     * skyline. It is stored as a list of segments, ordered by {@code x}, that cover {@code [0, width]} without gaps
     * or overlaps. Each segment is {@code {x, width, height}}, where height is how far forward ({@code z}) things
     * already reach over that stretch.
     *
     * <pre>
     *   x →  0          4      6        10
     *  z  ┌──────────┬──────┬──────────┐
     *  ↓  │  item A  │  B   │  item C  │
     *     │          ├──────┘          │     skyline: {0,4,3} {4,2,1} {6,4,3}
     *     └──────────┘      └──────────┘
     * </pre>
     *
     * Each item, in order, goes where its front edge ({@code z + depth}) ends up furthest back. Only the left ends
     * of segments are tried as positions: an item starting there rests on the highest segment it spans. On ties the
     * leftmost position wins. Positions are written to {@code xs}/{@code zs}.
     *
     * <p>Items never go under an overhang: the region behind the skyline is treated as full even where it has holes.
     * That wastes a little space, but keeps the algorithm simple and fast (O(n · segments) per item).
     */
    private static double[] skyline(List<Item> items, double width, double[] xs, double[] zs) {
        // Coordinates are sums of doubles, so compare with a tolerance; otherwise a rounding error could make an
        // item that exactly fills a gap look too big for it.
        final double eps = 1e-9;
        // Each segment: {x, width, height}. Initially the floor of the whole strip, at height 0.
        List<double[]> sky = new ArrayList<>();
        sky.add(new double[]{0, width, 0});
        // Bounding box of everything placed so far.
        double usedW = 0;
        double usedD = 0;
        for (int n = 0; n < items.size(); n++) {
            Item it = items.get(n);
            int bestIdx = -1;
            double bestTop = Double.MAX_VALUE;
            double bestY = 0;
            // Try the item's left edge at the start of each segment.
            for (int i = 0; i < sky.size(); i++) {
                double x = sky.get(i)[0];
                // Segments are ordered by x, so once the item sticks out on the right it does so for every later
                // segment too. Segment 0 always fits, because the strip is at least as wide as the widest item, so
                // bestIdx is always found.
                if (x + it.w > width + eps) {
                    break;
                }
                // The item rests on the highest of the segments under it: walk right until its width is covered.
                double y = 0;
                double remaining = it.w;
                for (int j = i; j < sky.size() && remaining > eps; j++) {
                    y = Math.max(y, sky.get(j)[2]);
                    remaining -= sky.get(j)[1];
                }
                // Keep the position whose front edge is furthest back. Strictly less (by more than eps) means a
                // later position must be really better, so ties go to the leftmost.
                if (y + it.d < bestTop - eps) {
                    bestTop = y + it.d;
                    bestIdx = i;
                    bestY = y;
                }
            }
            double x = sky.get(bestIdx)[0];
            xs[n] = x;
            zs[n] = bestY;
            usedW = Math.max(usedW, x + it.w);
            usedD = Math.max(usedD, bestY + it.d);

            // Update the skyline. The item adds a new segment covering [x, end) at its front edge. It goes in before
            // segment bestIdx (which starts at x too); then the segments it now covers are removed or shortened.
            double end = x + it.w;
            sky.add(bestIdx, new double[]{x, it.w, bestY + it.d});
            int k = bestIdx + 1;
            while (k < sky.size()) {
                double[] seg = sky.get(k);
                double segEnd = seg[0] + seg[1];
                // Starts at or after the item's right edge: nothing more to cover.
                if (seg[0] >= end - eps) {
                    break;
                }
                // Completely under the item: gone. Otherwise it sticks out on the right: keep only that part.
                if (segEnd <= end + eps) {
                    sky.remove(k);
                } else {
                    seg[1] = segEnd - end;
                    seg[0] = end;
                    break;
                }
            }
            // Merge neighbours of equal height. Fewer segments make later searches faster, and a wide flat segment
            // lets a wide item start at its left end. Walking backwards keeps indices valid while removing.
            for (int i = sky.size() - 1; i > 0; i--) {
                double[] a = sky.get(i - 1);
                double[] b = sky.get(i);
                if (Math.abs(a[2] - b[2]) < eps) {
                    a[1] += b[1];
                    sky.remove(i);
                }
            }
        }
        return new double[]{usedW, usedD};
    }
}
