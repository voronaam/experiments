//! A benchmark: test code by its directory.

use geo::shapes::polygon::Polygon;

fn main() {
    for _ in 0..1000 {
        let _ = Polygon::new(vec![]).perimeter();
    }
}
