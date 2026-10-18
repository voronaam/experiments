package dev.codecity.history;

import dev.codecity.analysis.TypeInfo;

import java.util.Arrays;
import java.util.Set;

/**
 * One type's metrics in every snapshot of the replay, one array slot per snapshot (oldest first).
 *
 * <p>{@code nom}, {@code noa}, {@code loc} and {@code cx} are -1 where the type does not exist. {@code commits},
 * {@code authors} and {@code last} are its file's git activity up to the snapshot's time (see
 * {@link CityHistory}); 0, 0 and -1 where the type does not exist or its file had no commit yet.
 *
 * <p>The arrays are filled in place while {@link CityHistory#compute} walks the snapshots and must be treated as
 * read-only afterwards. {@code latest}/{@code latestDeps} are the type as last seen in a snapshot (its file already
 * mapped back into the analysed root) and its dependencies then; they stand in for a type that no longer exists today.
 */
public final class TypeHistory {

    public final int[] nom;
    public final int[] noa;
    public final int[] loc;
    public final int[] cx;
    public final int[] commits;
    public final int[] authors;
    public final long[] last;
    TypeInfo latest;
    Set<String> latestDeps = Set.of();

    /** A type that exists in none of the {@code snapshots} (yet): metrics -1, no commits. */
    public TypeHistory(int snapshots) {
        nom = filled(snapshots, -1);
        noa = filled(snapshots, -1);
        loc = filled(snapshots, -1);
        cx = filled(snapshots, -1);
        commits = new int[snapshots];
        authors = new int[snapshots];
        last = new long[snapshots];
        Arrays.fill(last, -1);
    }

    private static int[] filled(int n, int v) {
        int[] a = new int[n];
        Arrays.fill(a, v);
        return a;
    }

    /** Whether the type exists in snapshot {@code k}. */
    public boolean exists(int k) {
        return nom[k] >= 0;
    }

    /** Largest attribute count over all snapshots (-1 when it exists in none); sizes the replay footprint. */
    public int maxNoa() {
        return Arrays.stream(noa).max().orElse(-1);
    }

    /** The type as it was in the latest snapshot containing it, or null. */
    public TypeInfo latest() {
        return latest;
    }

    /** Fqns the {@link #latest} version depended on (all of them types of that snapshot). */
    public Set<String> latestDeps() {
        return latestDeps;
    }
}
