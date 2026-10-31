//! A directory module (mod.rs): structs, an enum, a union, inherent and trait impls.

pub mod polygon;

use super::util::{self, clamp as limit};
use crate::Area;

/// A plain struct with named fields.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Point {
    pub x: f64,
    pub y: f64,
}

/// An enum with unit, tuple and struct variants.
pub enum Shape {
    Empty,
    Circle(Point, f64),
    Rect { min: Point, max: Point },
}

/// A union: two fields.
pub union Bits {
    pub int: u64,
    pub float: f64,
}

impl Shape {
    pub fn circle(center: Point, radius: f64) -> Self {
        Shape::Circle(center, limit(radius, 0.0, 100.0))
    }

    pub fn width(&self) -> f64 {
        match self {
            Shape::Empty => 0.0,
            Shape::Circle(_, r) => util::clamp(2.0 * r, 0.0, 1e9),
            Shape::Rect { min, max } => max.x - min.x,
        }
    }
}

impl Area for Shape {
    fn area(&self) -> f64 {
        match self {
            Shape::Circle(_, r) if *r > 0.0 => util::PI * r * r,
            Shape::Rect { min, max } => (max.x - min.x) * (max.y - min.y),
            _ => 0.0,
        }
    }
}

impl Default for Point {
    fn default() -> Self {
        Point { x: 0.0, y: 0.0 }
    }
}
