package com.acme.core;

/** Something with an area. An interface constant, an abstract, a default and a static method. */
public interface Shape extends Comparable<Shape> {
    double PI_ISH = 3.14;

    double area();

    default int compareTo(Shape other) {
        return Double.compare(area(), other.area());
    }

    static Shape unit() {
        return new Circle(1.0);
    }
}
