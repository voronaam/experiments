package com.acme.core;

/** Same package as Circle, so no import is needed. */
class CircleTest {
    void area() {
        assert new Circle(2).area() > 12;
    }

    void negative() {
        try {
            new Circle(-1);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError();
    }
}
