package org.mix;

import java.util.List;

/** The Java part: module "backend". */
public class Service {
    private final List<String> names;

    public Service(List<String> names) {
        this.names = names;
    }

    public int count(String prefix) {
        int n = 0;
        for (String s : names) {
            if (s.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }
}
