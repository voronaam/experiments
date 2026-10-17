package dev.codecity.layout;

import dev.codecity.analysis.TypeInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Lays the city out: packages become nested, stacked districts and types become buildings whose footprint grows with
 * their number of attributes. Each district's contents are packed with a skyline bin-packer, trying several strip
 * widths and keeping the most compact, square-ish result.
 */
public final class CityLayout {

    /** Thickness of one district platform. */
    public static final double PLATFORM = 0.4;
    /** Street width between neighbouring lots. */
    public static final double GAP = 0.8;
    /** Kerb between a district's edge and its contents. */
    public static final double PAD = 1.0;

    public record District(String id, String label, int depth, double x, double z, double w, double d, int types) {
    }

    /** Where a building stands: footprint centre, side length and the height of the ground it stands on. */
    public record Lot(double x, double z, double side, double y, int districtIndex) {
    }

    public record Result(List<District> districts, Map<String, Lot> lots, double width, double depth) {
    }

    public static double side(TypeInfo t) {
        return 1.2 + 0.8 * Math.sqrt(t.noa());
    }

    private static final class Node {
        final String label;
        final String id;
        final Map<String, Node> children = new TreeMap<>();
        final List<TypeInfo> types = new ArrayList<>();
        boolean module;
        /** How this district's name joins with a collapsed child's: ".", "/" or "::". */
        String separator = ".";
        double w;
        double d;
        List<Item> items = List.of();

        Node(String label, String id) {
            this.label = label;
            this.id = id;
        }

        int typeCount() {
            return types.size() + children.values().stream().mapToInt(Node::typeCount).sum();
        }
    }

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
        Node root = buildTree(cityName, types);
        measure(root);
        List<District> districts = new ArrayList<>();
        Map<String, Lot> lots = new HashMap<>();
        place(root, -root.w / 2, -root.d / 2, 0, districts, lots);
        return new Result(districts, lots, root.w, root.d);
    }

    private static Node buildTree(String cityName, List<TypeInfo> types) {
        boolean multiModule = types.stream().map(TypeInfo::module).distinct().count() > 1;
        Node root = new Node(cityName, "");
        for (TypeInfo t : types) {
            Node node = root;
            if (multiModule) {
                String module = t.module().isEmpty() ? "(root)" : t.module();
                node = node.children.computeIfAbsent("module:" + module, k -> new Node(module, k));
                node.module = true;
            }
            List<String> segments = t.packageSegments();
            if (segments.isEmpty()) {
                segments = List.of(t.language() == dev.codecity.analysis.Language.JAVA ? "(default)" : "(root)");
            }
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

    /** {@code com → acme → shop} with nothing in between becomes a single {@code com.acme.shop} district. */
    private static void collapse(Node node) {
        Map<String, Node> merged = new TreeMap<>();
        for (Node child : node.children.values()) {
            Node c = child;
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
            collapse(c);
            merged.put(c.label, c);
        }
        node.children.clear();
        node.children.putAll(merged);
    }

    private static void measure(Node node) {
        List<Item> items = new ArrayList<>();
        for (Node child : node.children.values()) {
            measure(child);
            items.add(new Item(child.w + GAP, child.d + GAP, child, null));
        }
        for (TypeInfo t : node.types) {
            double s = side(t);
            items.add(new Item(s + GAP, s + GAP, null, t));
        }
        double[] size = pack(items);
        node.items = items;
        node.w = Math.max(size[0] - GAP, 0) + 2 * PAD;
        node.d = Math.max(size[1] - GAP, 0) + 2 * PAD;
    }

    private static void place(Node node, double left, double top, int depth,
                              List<District> districts, Map<String, Lot> lots) {
        int index = districts.size();
        districts.add(new District(node.id, node.label, depth,
                left + node.w / 2, top + node.d / 2, node.w, node.d, node.typeCount()));
        for (Item item : node.items) {
            double x = left + PAD + item.x;
            double z = top + PAD + item.z;
            if (item.node != null) {
                place(item.node, x, z, depth + 1, districts, lots);
            } else {
                double s = side(item.type);
                lots.put(item.type.fqn(), new Lot(x + s / 2, z + s / 2, s, (depth + 1) * PLATFORM, index));
            }
        }
    }

    /** Packs items into a compact rectangle; assigns item positions and returns {width, depth}. */
    static double[] pack(List<Item> items) {
        if (items.isEmpty()) {
            return new double[]{0, 0};
        }
        items.sort(Comparator.<Item>comparingDouble(i -> Math.max(i.w, i.d))
                .thenComparingDouble(i -> i.w * i.d).reversed());
        double area = 0;
        double maxW = 0;
        for (Item i : items) {
            area += i.w * i.d;
            maxW = Math.max(maxW, i.w);
        }
        double[] xs = new double[items.size()];
        double[] zs = new double[items.size()];
        double[] bestX = null;
        double[] bestZ = null;
        double[] bestSize = null;
        double bestScore = Double.MAX_VALUE;
        for (double f : new double[]{0.85, 1.0, 1.12, 1.25, 1.45, 1.7}) {
            double width = Math.max(maxW, Math.sqrt(area) * f);
            double[] size = skyline(items, width, xs, zs);
            double score = size[0] * size[1] * (1 + 0.35 * Math.abs(Math.log(size[0] / size[1])));
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

    /** Bottom-left skyline packing into a strip of the given width. Returns {usedWidth, depth}. */
    private static double[] skyline(List<Item> items, double width, double[] xs, double[] zs) {
        final double eps = 1e-9;
        // Each segment: {x, width, height}
        List<double[]> sky = new ArrayList<>();
        sky.add(new double[]{0, width, 0});
        double usedW = 0;
        double usedD = 0;
        for (int n = 0; n < items.size(); n++) {
            Item it = items.get(n);
            int bestIdx = -1;
            double bestTop = Double.MAX_VALUE;
            double bestY = 0;
            for (int i = 0; i < sky.size(); i++) {
                double x = sky.get(i)[0];
                if (x + it.w > width + eps) {
                    break;
                }
                double y = 0;
                double remaining = it.w;
                for (int j = i; j < sky.size() && remaining > eps; j++) {
                    y = Math.max(y, sky.get(j)[2]);
                    remaining -= sky.get(j)[1];
                }
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

            double end = x + it.w;
            sky.add(bestIdx, new double[]{x, it.w, bestY + it.d});
            int k = bestIdx + 1;
            while (k < sky.size()) {
                double[] seg = sky.get(k);
                double segEnd = seg[0] + seg[1];
                if (seg[0] >= end - eps) {
                    break;
                }
                if (segEnd <= end + eps) {
                    sky.remove(k);
                } else {
                    seg[1] = segEnd - end;
                    seg[0] = end;
                    break;
                }
            }
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
