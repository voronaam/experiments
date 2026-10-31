package com.acme.core;

import java.util.Objects;

/** A record: one component, a compact constructor (not a method) and a varargs factory. */
@Audited(owner = "geometry")
public record Circle(double radius) implements Shape {
    public Circle {
        if (radius < 0 || Double.isNaN(radius)) {
            throw new IllegalArgumentException("radius");
        }
    }

    public double area() {
        return Shape.PI_ISH * radius * radius;
    }

    public static Circle of(double... radii) {
        double sum = 0;
        for (double r : radii) {
            sum += r;
        }
        return new Circle(Objects.requireNonNull(sum));
    }
}
