package com.acme.core.util;

/** Static helpers in an abstract class (kind "abstract"). */
public abstract class Strings {
    private Strings() {
    }

    public static String repeat(String s, int times) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < times) {
            b.append(s);
            i++;
        }
        return b.toString();
    }

    public static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    protected abstract void hook();
}
