package dev.codecity.desktop;

import dev.codecity.CityModel.Building;
import dev.codecity.analysis.Language;
import dev.codecity.analysis.TypeInfo;
import dev.codecity.github.PullRequest;
import dev.codecity.layout.CityLayout;
import javafx.scene.paint.Color;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.IntPredicate;

/**
 * The desktop viewer's rules as pure functions: what the search finds, how tall buildings are, which districts get a
 * label, which dependency arcs are drawn, what the inspector and the tooltip say, and where walk mode starts.
 *
 * <p>{@link DesktopApp}, {@link CityView3D} and {@link InspectorPane} call these with the current state and show the
 * result; nothing here needs a window, so every rule is tested without a display. The Rust ({@code gui/rules.rs}) and
 * D ({@code gui/rules.d}) desktop viewers implement the same rules with the same numbers, and {@code ViewRulesTest}
 * checks the same scenarios as their tests, so all three show a city identically. Coordinates are the layout's (y up,
 * z towards the viewer); {@link CityView3D} converts them to its scene.
 */
final class ViewRules {

    private ViewRules() {
    }

    // ------------------------------------------------------------------ search

    /** Queries shorter than this (in UTF-16 code units, as in the browser viewer) clear the search. */
    static final int MIN_QUERY = 2;
    /** How many of the best matches the drop-down under the search field lists. */
    static final int MAX_RESULTS = 12;

    /**
     * Searches the types that pass {@code visible} for {@code query} (trimmed, case-insensitive) and returns the indices
     * of every match, best first; null when the query is too short, which means "no search active".
     *
     * <p>Ranking: exact simple name, name prefix, name substring, then a match anywhere in the fully qualified name;
     * within a rank, shorter names (in UTF-16 units) first. The sort is stable, so equal entries keep model order. The
     * viewer highlights every match in the city and lists the first {@link #MAX_RESULTS}.
     */
    static int[] search(String query, List<TypeInfo> types, IntPredicate visible) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        if (q.length() < MIN_QUERY) {
            return null;
        }
        record Scored(int i, int score, int len) {
        }
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            if (!visible.test(i)) {
                continue;
            }
            TypeInfo t = types.get(i);
            String name = t.name().toLowerCase(Locale.ROOT);
            int score = name.equals(q) ? 0 : name.startsWith(q) ? 1 : name.contains(q) ? 2
                    : t.fqn().toLowerCase(Locale.ROOT).contains(q) ? 3 : -1;
            if (score >= 0) {
                scored.add(new Scored(i, score, name.length()));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).thenComparingInt(Scored::len));
        return scored.stream().mapToInt(Scored::i).toArray();
    }

    // ------------------------------------------------------------------ heights

    /** The scale of camera distances, heights and shadows: half the city's diagonal, at least 20. */
    static double radius(double width, double depth) {
        return Math.max(20, Math.hypot(width, depth) / 2);
    }

    /**
     * Height per unit of the height metric for a city of {@code radius} whose visible buildings have the metric
     * {@code values}: the 95th-percentile value (nearest rank, rounding the rank down; at least 1, so an all-zero metric
     * does not divide by zero) becomes 20% of the radius, kept between 8 and 16 units.
     */
    static double heightScale(double[] values, double radius) {
        double[] v = values.clone();
        java.util.Arrays.sort(v);
        double p95 = Math.max(1, v.length == 0 ? 1 : v[Math.min(v.length - 1, (int) (0.95 * (v.length - 1)))]);
        return Math.min(16, Math.max(8, radius * 0.2)) / p95;
    }

    /**
     * The height of a visible building whose metric is {@code value}: a 0.35 base, so a value of 0 still shows, plus
     * the value on {@code scale}, capped at 75% of the radius so outliers do not tower over everything.
     */
    static double height(double value, double scale, double radius) {
        return Math.min(0.35 + value * scale, radius * 0.75);
    }

    // ------------------------------------------------------------------ colours

    /**
     * The colour metric in effect: {@code chosen} (null when the user never picked one) when this model can provide
     * it, else churn when there is git history, else complexity.
     */
    static Look.ColorMetric effectiveColor(Look.ColorMetric chosen, boolean hasGit, boolean hasPulls) {
        if (chosen != null && chosen.available(hasGit, hasPulls)) {
            return chosen;
        }
        return hasGit ? Look.ColorMetric.COMMITS : Look.ColorMetric.CX;
    }

    /**
     * Whether building {@code i} stays undimmed while {@code selected} is selected: the selection itself and its
     * direct neighbours in both directions (what it uses, what uses it). Everything is related when nothing is
     * selected (null).
     */
    static boolean related(Building selected, int i) {
        if (selected == null || selected.index() == i) {
            return true;
        }
        for (int j : selected.deps()) {
            if (j == i) {
                return true;
            }
        }
        for (int j : selected.incoming()) {
            if (j == i) {
                return true;
            }
        }
        return false;
    }

    /**
     * A building's final colour from its metric colour {@code base}: mixed 80% towards the theme's {@code dim} colour
     * when it is outside the search matches or unrelated to the selection, then lightened 30% towards white when it is
     * hovered (and not the selected one).
     */
    static Color shade(Color base, Color dim, boolean outOfSearch, boolean outOfFocus, boolean hovered) {
        Color c = base;
        if (outOfSearch || outOfFocus) {
            c = c.interpolate(dim, 0.8);
        }
        if (hovered) {
            c = c.interpolate(Color.WHITE, 0.3);
        }
        return c;
    }

    // ------------------------------------------------------------------ districts and labels

    /**
     * Whether district {@code d} is part of the present-day view: always, except in a history city when its type count
     * is 0, as it then holds only gone types (and would be an empty platform today).
     */
    static boolean presentDistrict(CityLayout.District d, boolean history) {
        return !history || d.types() > 0;
    }

    /** At most this many districts are considered for a label, largest first. */
    static final int LABEL_CANDIDATES = 24;
    /** At most this many labels are placed. */
    static final int MAX_LABELS = 16;

    /** A district name label: the text, its anchor in layout space and the district's index. */
    record Label(String text, double x, double y, double z, int district) {
    }

    /**
     * Chooses which districts get a floating name label.
     *
     * <p>Labelling every district would bury the city in text, so only districts at nesting depth 1 and 2 (modules and
     * top-level packages; depth 0 is the city itself) that contain at least one type are candidates. The
     * {@link #LABEL_CANDIDATES} largest by area are considered, biggest first (equal areas in model order), and a label
     * is skipped if its anchor is within 8 units sideways and 3 units in depth of one already placed, since the two
     * would overlap on screen. At most {@link #MAX_LABELS} labels are kept.
     *
     * <p>Each label is anchored at the middle of the district's front edge ({@code z + d/2}), 0.2 above its platform: a
     * district at depth {@code n} has its top at {@code (n + 1) * PLATFORM}, as in the layout.
     */
    static List<Label> labels(List<CityLayout.District> districts) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < districts.size(); i++) {
            CityLayout.District d = districts.get(i);
            if (d.depth() >= 1 && d.depth() <= 2 && d.types() > 0) {
                order.add(i);
            }
        }
        order.sort((a, b) -> Double.compare(districts.get(b).w() * districts.get(b).d(),
                districts.get(a).w() * districts.get(a).d()));
        List<Label> labels = new ArrayList<>();
        for (int index : order.subList(0, Math.min(LABEL_CANDIDATES, order.size()))) {
            CityLayout.District d = districts.get(index);
            double front = d.z() + d.d() / 2;
            boolean crowded = labels.stream().anyMatch(l -> Math.abs(l.x() - d.x()) < 8 && Math.abs(l.z() - front) < 3);
            if (crowded || labels.size() >= MAX_LABELS) {
                continue;
            }
            labels.add(new Label(d.label(), d.x(), (d.depth() + 1) * CityLayout.PLATFORM + 0.2, front, index));
        }
        return labels;
    }

    // ------------------------------------------------------------------ dependency arcs

    /** At most this many arcs are drawn per direction (uses / used by). */
    static final int MAX_ARCS = 200;
    /** Above this many dependency edges in total (before filtering and capping), arcs are drawn thinner. */
    static final int THIN_ARCS_ABOVE = 40;

    /**
     * One dependency arc: from the depending building to its dependency, and whether it starts at the selection (an
     * outgoing "uses" arc, orange) or ends there (an incoming "used by" arc, blue).
     */
    record ArcEnds(int from, int to, boolean outgoing) {
    }

    /**
     * The arcs to draw for the selection: none when nothing is selected (null) or the selection is hidden; otherwise
     * one to every visible type it uses, then one from every visible type that uses it, each direction capped at
     * {@link #MAX_ARCS} in model order.
     */
    static List<ArcEnds> arcs(Building selected, IntPredicate visible) {
        List<ArcEnds> arcs = new ArrayList<>();
        if (selected == null || !visible.test(selected.index())) {
            return arcs;
        }
        int sel = selected.index();
        int count = 0;
        for (int j : selected.deps()) {
            if (visible.test(j) && count++ < MAX_ARCS) {
                arcs.add(new ArcEnds(sel, j, true));
            }
        }
        count = 0;
        for (int j : selected.incoming()) {
            if (visible.test(j) && count++ < MAX_ARCS) {
                arcs.add(new ArcEnds(j, sel, false));
            }
        }
        return arcs;
    }

    /**
     * The height of the Bézier control point of an arc between roofs at heights {@code y0} and {@code y2}, {@code dist}
     * apart (it sits above their midpoint): 2 units plus 35% of their distance higher than the taller roof, so longer
     * arcs rise higher and neighbours still get a visible hump.
     */
    static double arcControlHeight(double y0, double y2, double dist) {
        return Math.max(y0, y2) + dist * 0.35 + 2;
    }

    /** The tube radius of an arc of length {@code dist} when the selection has {@code total} edges. */
    static double arcRadius(int total, double dist) {
        return (total > THIN_ARCS_ABOVE ? 0.03 : 0.055) + dist * 0.0005;
    }

    /**
     * The particle speed on an arc of length {@code dist}, in curve parameter per second: about 14 / 1.2 ≈ 12 world
     * units per second along the straight line, at most 2.8 trips per second on short arcs.
     */
    static double particleSpeed(double dist) {
        return 14 / Math.max(5, dist * 1.2);
    }

    // ------------------------------------------------------------------ inspector and tooltip

    /** How many of the most complex methods the inspector lists. */
    static final int INSPECTOR_METHODS = 15;

    /**
     * The inspector's line under the type name: the package (or {@code (default package)} for Java, {@code (root)}
     * otherwise) and the module after a dot, if any.
     */
    static String packageLine(TypeInfo t) {
        String pkg = !t.packageName().isEmpty() ? t.packageName() : t.language() == Language.JAVA ? "(default package)" : "(root)";
        return t.module().isEmpty() ? pkg : pkg + " · " + t.module();
    }

    /**
     * The inspector's metric tiles, as {value, label} in display order: methods, attributes, lines, complexity,
     * complexity per method (one decimal) and the visible dependency counts; with git history also commits, authors
     * and the age of the last change relative to {@code now} (a file without history shows 0, 0 and "—").
     */
    static List<String[]> metricCells(Building b, boolean hasGit, int uses, int usedBy, long now) {
        TypeInfo t = b.type();
        List<String[]> cells = new ArrayList<>(List.of(
                new String[]{String.valueOf(t.nom()), "methods"},
                new String[]{String.valueOf(t.noa()), "attributes"},
                new String[]{Look.fmt(t.loc()), "lines of code"},
                new String[]{String.valueOf(t.complexity()), "complexity"},
                new String[]{t.nom() == 0 ? "0" : Look.ratio(t.complexity(), t.nom()), "cx / method"},
                new String[]{uses + " / " + usedBy, "uses / used by"}));
        if (hasGit) {
            var h = b.history();
            cells.add(new String[]{h == null ? "0" : String.valueOf(h.commits()), "commits"});
            cells.add(new String[]{h == null ? "0" : String.valueOf(h.authors()), "authors"});
            cells.add(new String[]{h == null ? "—" : Look.relTime(h.lastCommit(), now), "last change"});
        }
        return cells;
    }

    /** One method in the inspector's list: its name with {@code (…)} when it takes parameters, else {@code ()}. */
    static String methodName(String name, int parameters) {
        return name + (parameters > 0 ? "(…)" : "()");
    }

    /**
     * The line under the method list when the type has more methods than are listed (null otherwise): counted on all
     * of the type's methods ({@code total}), like the section header, not on the model's capped list of the most
     * complex 300.
     */
    static String moreMethods(int total) {
        return total > INSPECTOR_METHODS ? "+ " + (total - INSPECTOR_METHODS) + " more" : null;
    }

    /**
     * The second line of an open pull request in the inspector: author ({@code ghost} when unknown), how long ago it
     * was updated relative to {@code now} ("—" when the timestamp is missing or does not parse), its file count and
     * whether it is a draft.
     */
    static String prMeta(PullRequest pr, long now) {
        return (pr.author() == null ? "ghost" : pr.author()) + " · " + Look.relTime(parseIso(pr.updatedAt()), now)
                + " · " + pr.files().size() + " files" + (pr.draft() ? " · draft" : "");
    }

    /**
     * What the inspector says when no fetched pull request touches the file: plain "none" when every open PR was
     * fetched, otherwise which ones were searched (e.g. "none among the latest 30 open PRs (no token)").
     */
    static String noPulls(String source) {
        String src = source == null ? "" : source;
        return src.startsWith("all") ? "none" : "none among the " + src;
    }

    /**
     * Seconds since the epoch for an ISO-8601 timestamp such as {@code 2026-09-30T12:00:00Z} or
     * {@code 2026-09-30T14:00:00+02:00}; 0 (shown as "—" by {@link Look#relTime}) when it is missing or does not parse.
     */
    static long parseIso(String s) {
        if (s == null) {
            return 0;
        }
        try {
            return Instant.parse(s).getEpochSecond();
        } catch (java.time.format.DateTimeParseException e) {
            return 0;
        }
    }

    /**
     * The numbers row of a building's hover card in the present-day city: methods, attributes, lines, complexity, then
     * the commit count when the file has history and the open pull requests touching it, if any.
     */
    static String tooltipRow(Building b) {
        TypeInfo t = b.type();
        StringBuilder sb = new StringBuilder().append(t.nom()).append(" methods · ").append(t.noa()).append(" attrs · ")
                .append(Look.fmt(t.loc())).append(" loc · cx ").append(t.complexity());
        if (b.history() != null) {
            sb.append(" · ").append(b.history().commits()).append(" commits");
        }
        if (b.prs().length > 0) {
            sb.append(" · ").append(b.prs().length).append(" open PR").append(b.prs().length > 1 ? "s" : "");
        }
        return sb.toString();
    }

    /**
     * A district's hover card as {title, id, row}, or null for the city platform itself (depth 0). Module ids carry a
     * {@code module:} prefix in the layout to keep them apart from packages, which is not shown; the double-click hint
     * is left out while walking, where double-clicks do nothing.
     */
    static String[] districtTooltip(CityLayout.District d, boolean walking) {
        if (d.depth() == 0) {
            return null;
        }
        String id = d.id().startsWith("module:") ? d.id().substring("module:".length()) : d.id();
        return new String[]{d.label(), id, d.types() + " types" + (walking ? "" : " · double-click to zoom")};
    }

    /**
     * The numbers row of a building's hover card during a replay, for snapshot {@code k} taken on {@code date}: the
     * snapshot's methods, attributes, lines and complexity (0 where the type does not exist), its commit count when the
     * file has git statistics today, and the date; no pull requests, which belong to today. The same text as the
     * browser viewer's.
     */
    static String replayTooltipRow(Building b, int k, String date) {
        var h = b.hist();
        StringBuilder sb = new StringBuilder().append(Math.max(0, h.nom[k])).append(" methods · ")
                .append(Math.max(0, h.noa[k])).append(" attrs · ").append(Look.fmt(Math.max(0, h.loc[k])))
                .append(" loc · cx ").append(Math.max(0, h.cx[k]));
        if (b.history() != null) {
            sb.append(" · ").append(h.commits[k]).append(" commits");
        }
        return sb.append(" · at ").append(date).toString();
    }

    /** The inspector's heading of the replay box for a snapshot taken on {@code date}. */
    static String replayHeading(String date) {
        return "Replaying — values at " + date;
    }

    /** What the inspector says about a type that does not exist in the replayed snapshot. */
    static final String NOT_IN_SNAPSHOT = "Not in this snapshot";
    /** What the inspector says about a type that no longer exists today. */
    static final String REMOVED = "Removed before today";

    /**
     * The inspector's replay tiles for snapshot {@code k} as {value, label}: methods, attributes, lines of code and
     * complexity; null when the type does not exist there (the inspector then says {@link #NOT_IN_SNAPSHOT}).
     */
    static List<String[]> replayCells(Building b, int k) {
        var h = b.hist();
        if (!h.exists(k)) {
            return null;
        }
        return List.of(new String[]{String.valueOf(h.nom[k]), "methods"}, new String[]{String.valueOf(h.noa[k]), "attributes"},
                new String[]{Look.fmt(h.loc[k]), "lines of code"}, new String[]{String.valueOf(h.cx[k]), "complexity"});
    }

    // ------------------------------------------------------------------ walk mode

    /**
     * Where walk mode starts, as {x, z, yaw} for {@link Walker#spawn}: with a building selected ({@code selected} not
     * null), on the street in front of it (its +z side, just clear of the wall: half its lot side, the walker's radius
     * and 0.2 out from its centre), facing it (yaw 0 looks towards -z); otherwise at the orbit target
     * ({@code targetX}, {@code targetZ}), facing the way the orbit camera looks ({@code yaw}, in the walker's
     * convention).
     */
    static double[] walkStart(CityLayout.Lot selected, double targetX, double targetZ, double yaw) {
        if (selected != null) {
            return new double[]{selected.x(), selected.z() + selected.side() / 2 + Walker.RADIUS + 0.2, 0};
        }
        return new double[]{targetX, targetZ, yaw};
    }
}
