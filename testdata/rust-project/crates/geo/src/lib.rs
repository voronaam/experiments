//! The library crate root: file modules, an inline module, a trait, free items and unit tests.

pub mod shapes;
pub mod util;

/// Anything with an area; implemented in shapes/mod.rs.
pub trait Area {
    fn area(&self) -> f64;

    fn is_empty(&self) -> bool {
        self.area() == 0.0
    }
}

pub const ORIGIN: (f64, f64) = (0.0, 0.0);
static mut COUNTER: u32 = 0;

/// A free function of the crate root's module building.
pub fn total(items: &[&dyn Area]) -> f64 {
    let mut sum = 0.0;
    for item in items {
        if !item.is_empty() {
            sum += item.area();
        }
    }
    sum
}

/// An inline module: its own district and module building.
pub mod units {
    pub struct Meters(pub f64);

    pub fn to_feet(m: &Meters) -> f64 {
        m.0 * 3.28
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Fake;

    impl Area for Fake {
        fn area(&self) -> f64 {
            1.0
        }
    }

    #[test]
    fn totals() {
        assert_eq!(total(&[&Fake]), 1.0);
    }
}
