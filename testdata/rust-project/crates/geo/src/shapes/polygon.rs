//! A tuple struct, a generic impl and an impl of a foreign trait for a foreign type.

use super::Point;
use crate::shapes::*;
use std::fmt;

/// A tuple struct: one field.
pub struct Polygon(pub Vec<Point>);

impl<'a> Polygon {
    pub fn new(points: Vec<Point>) -> Self {
        Polygon(points)
    }

    pub fn perimeter(&self) -> f64 {
        let mut total = 0.0;
        let mut i = 0;
        while i < self.0.len() {
            let a = self.0[i];
            let b = self.0[(i + 1) % self.0.len()];
            total += ((b.x - a.x).powi(2) + (b.y - a.y).powi(2)).sqrt();
            i += 1;
        }
        total
    }

    pub fn bounds(&self) -> Option<Shape> {
        let first = self.0.first()?;
        Some(Shape::Rect { min: *first, max: *first })
    }
}

impl fmt::Display for Polygon {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "polygon of {}", self.0.len())
    }
}

/// A foreign trait for a foreign type: goes to this module's module building, created for it.
impl From<Polygon> for Vec<Point> {
    fn from(p: Polygon) -> Self {
        p.0
    }
}
