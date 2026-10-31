package com.acme.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A generic registry of shapes: member types of every kind, a local and an anonymous class (neither is a building),
 * and every decision point of McCabe complexity.
 */
public class Registry<T extends Shape> {
    private final Map<String, Entry<T>> entries = new HashMap<>();
    private int hits, misses;
    static int created;

    /* A block comment
       spanning two lines. */
    public Registry() {
        created++;
    }

    // if, ||, else if, &&: complexity 5
    public boolean add(String name, T shape) {
        if (name == null || name.isEmpty()) {
            return false;
        } else if (shape.area() < 0.5 && !name.startsWith("tiny")) {
            return false;
        }
        entries.put(name, new Entry<>(name, shape));
        return true;
    }

    // if, ?: : complexity 3
    public T find(String name) {
        Entry<T> e = entries.get(name);
        if (e != null) {
            hits++;
        } else {
            misses++;
        }
        return e == null ? null : e.shape();
    }

    // for-each, if: complexity 3
    public List<T> matching(Predicate<T> filter) {
        List<T> out = new ArrayList<>();
        for (Entry<T> e : entries.values()) {
            if (filter.test(e.shape())) {
                out.add(e.shape());
            }
        }
        return out;
    }

    // for, while, do, two catches: complexity 6
    public int retries(int limit) {
        int n = 0;
        for (int i = 0; i < limit; i++) {
            n += i;
        }
        while (n > 100) {
            n /= 2;
        }
        do {
            n--;
        } while (n > 50);
        try {
            n = Integer.parseInt("" + n);
        } catch (NumberFormatException e) {
            n = 0;
        } catch (RuntimeException e) {
            n = -1;
        }
        return n;
    }

    // switch expression with a two-label case (counts once) and a default (does not count); lambda with &&: complexity 3
    public long weight(Mode mode) {
        int base = switch (mode) {
            case FAST, SAFE -> 1;
            default -> 2;
        };
        return entries.values().stream().filter(e -> e.name() != null && base > 0).count();
    }

    // anonymous and local classes are not buildings; their decision points count here: complexity 3
    public Runnable snapshot() {
        class Local {
            int value;
        }
        Local local = new Local();
        return new Runnable() {
            @Override
            public void run() {
                if (local.value > 0) {
                    local.value--;
                }
                for (String k : entries.keySet()) {
                    System.out.println(k);
                }
            }
        };
    }

    public <R> R accept(Listener<R> listener) {
        return listener.visit(this);
    }

    /** A static nested generic class. */
    public static final class Entry<S> {
        private final String name;
        private final S shape;

        Entry(String name, S shape) {
            this.name = name;
            this.shape = shape;
        }

        String name() {
            return name;
        }

        S shape() {
            return shape;
        }
    }

    /** An inner class with an inner class of its own (Registry.Cursor.Step). */
    public class Cursor {
        private int position;

        public boolean hasNext() {
            return position < entries.size();
        }

        class Step {
            int delta = 1;
        }
    }

    /** A nested interface. */
    public interface Listener<R> {
        R visit(Registry<?> registry);
    }

    /** A nested enum. */
    public enum Mode { FAST, SAFE, AUTO }

    /** A nested record. */
    record Stats(int hits, int misses) {
    }
}
