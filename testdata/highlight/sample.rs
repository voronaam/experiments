//! Module docs with 'quotes'.
use std::collections::HashMap;

#[derive(Debug, Clone)]
pub struct Holder<'a, T: Clone> {
    name: &'a str,
    items: Vec<T>,
}

const LIMIT: usize = 10_000;

impl<'a, T: Clone> Holder<'a, T> {
    /* nested? /* no */
    pub fn get(&self, k: char) -> Option<&T> {
        let c = 'x';
        let s = "multi
line string";
        let r = r"raw";
        if k == '\'' { return None; }
        self.items.first()
    }
}
