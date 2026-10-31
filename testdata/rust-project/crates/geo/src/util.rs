//! Free functions and a constant, plus an impl of a type declared in another module.

use crate::shapes::Point;

pub const PI: f64 = 3.14159;

pub fn clamp(v: f64, lo: f64, hi: f64) -> f64 {
    if v < lo {
        lo
    } else if v > hi {
        hi
    } else {
        v
    }
}

/// Methods may be added in any module of the crate: these belong to Point.
impl Point {
    pub fn norm(&self) -> f64 {
        (self.x * self.x + self.y * self.y).sqrt()
    }

    pub fn is_origin(&self) -> bool {
        self.x == 0.0 && self.y == 0.0 || self.norm() < 1e-9
    }
}
