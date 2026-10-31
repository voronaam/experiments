package com.acme.app;

import com.acme.core.Circle;
import com.acme.core.Registry;
import com.acme.core.util.*;

import static com.acme.core.Color.RED;
import static com.acme.core.Shape.unit;

/**
 * The second module. Single-type imports (Circle, Registry), an on-demand import (Strings) and static imports (Color,
 * Shape: calling an imported member names no type, so neither becomes a dependency). The annotation is written fully
 * qualified. Scratch is in the default package, which named packages cannot see.
 */
@com.acme.core.Audited(owner = "app")
public final class Main {
    private final Registry<Circle> registry = new Registry<>();
    private Scratch invisible;

    public static void main(String[] args) {
        Main m = new Main();
        m.registry.add("one", Circle.of(1, 2));
        System.out.println(Strings.repeat("=", 10) + RED.hex() + unit().area());
        Registry.Mode mode = args.length > 0 ? Registry.Mode.FAST : Registry.Mode.SAFE;
        System.out.println(m.registry.weight(mode));
    }
}
