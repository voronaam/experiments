//! The Rust part: crate "mix-native" becomes mix_native.

pub struct Counter {
    count: u64,
}

impl Counter {
    pub fn bump(&mut self, by: u64) -> u64 {
        self.count = self.count.saturating_add(by);
        self.count
    }
}

pub fn fresh() -> Counter {
    Counter { count: 0 }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bumps() {
        assert_eq!(fresh().bump(2), 2);
    }
}
