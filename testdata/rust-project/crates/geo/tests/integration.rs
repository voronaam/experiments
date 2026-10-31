//! An integration test: test code by its directory.

use geo::shapes::{Point, Shape};

#[test]
fn circle_width() {
    let c = Shape::circle(Point { x: 0.0, y: 0.0 }, 2.0);
    assert_eq!(c.width(), 4.0);
}
