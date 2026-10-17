package dev.codecity.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RustAnalyzerTest {

    @TempDir
    Path root;

    private void write(String rel, String code) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, code);
    }

    private Analysis analyze() throws IOException {
        return new SourceAnalyzer().analyze(root, true);
    }

    private static Map<String, TypeInfo> byFqn(Analysis a) {
        return a.types().stream().collect(Collectors.toMap(TypeInfo::fqn, Function.identity()));
    }

    @Test
    void buildsTypesFromStructsEnumsTraitsAndImplBlocksAcrossFiles() throws IOException {
        write("Cargo.toml", "[package]\nname = \"my-shop\"\nversion = \"0.1.0\"\n");
        write("src/lib.rs", """
                pub mod cart;
                pub mod pricing;
                pub use cart::Cart;
                pub fn version() -> &'static str { "1" }
                """);
        write("src/cart.rs", """
                use crate::pricing::{Price, Discount as D};
                pub struct Cart { items: Vec<Item>, owner: String }
                pub struct Item(u32, Price);
                pub enum State { Open, Paid { at: u64 }, Cancelled(String) }
                impl Cart {
                    pub fn new(owner: String) -> Self { Cart { items: vec![], owner } }
                    pub fn total(&self, d: &D) -> u32 {
                        let mut t = 0;
                        for i in &self.items { if i.0 > 0 && d.applies() { t += i.0; } }
                        match t { 0 => 0, 1..=9 => t, _ => t - 1 }
                    }
                }
                #[cfg(test)]
                mod tests {
                    use super::*;
                    #[test]
                    fn empty() { assert_eq!(Cart::new("a".into()).items.len(), 0); }
                }
                """);
        write("src/pricing/mod.rs", """
                pub struct Price(u32);
                pub trait Discount { fn applies(&self) -> bool; fn rate(&self) -> u32 { 0 } }
                """);
        write("src/pricing/extra.rs", """
                use super::Price;
                impl crate::cart::Cart { pub fn clear(&mut self) { self.items.clear(); } }
                impl std::fmt::Display for Price {
                    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result { write!(f, "{}", self.0) }
                }
                """);
        Analysis a = analyze();
        Map<String, TypeInfo> t = byFqn(a);

        TypeInfo cart = t.get("my_shop::cart::Cart");
        assertEquals("struct", cart.kind());
        assertEquals(Language.RUST, cart.language());
        assertEquals("cart", cart.packageName());
        assertEquals(2, cart.noa());
        assertEquals(List.of("new", "total", "clear"), cart.methods().stream().map(MethodInfo::name).toList());
        // total: 1 + for + if + && + (3 arms - 1)
        assertEquals(6, cart.methods().get(1).complexity());

        assertEquals(2, t.get("my_shop::cart::Item").noa(), "tuple struct fields");
        assertEquals(3, t.get("my_shop::cart::State").noa(), "variants");
        assertEquals(2, t.get("my_shop::pricing::Discount").nom());
        assertEquals("pricing", t.get("my_shop::pricing::Price").packageName());
        assertEquals(List.of("fmt"), t.get("my_shop::pricing::Price").methods().stream().map(MethodInfo::name).toList(),
                "a trait impl for a local type attaches to that type");

        TypeInfo tests = t.get("my_shop::cart::tests::{fn}");
        assertTrue(tests.test());
        assertEquals("cart", tests.packageName(), "cfg(test) modules stay in their parent's district");
        assertEquals("mod tests", tests.name());
        assertEquals("lib.rs", t.get("my_shop::{fn}").name());

        assertTrue(a.dependencies().get("my_shop::cart::Cart").contains("my_shop::pricing::Discount"),
                "aliased grouped import (Discount as D)");
        assertTrue(a.dependencies().get("my_shop::cart::Item").contains("my_shop::pricing::Price"));
        assertTrue(a.dependencies().get("my_shop::cart::tests::{fn}").contains("my_shop::cart::Cart"), "use super::*");
    }

    @Test
    void separatesWorkspaceCratesAndFindsCrateRootsOutsideSrc() throws IOException {
        write("Cargo.toml", "[workspace]\nmembers = [\"crates/*\"]\n");
        write("crates/core/Cargo.toml", "[package]\nname = \"core-lib\"\n");
        write("crates/core/src/lib.rs", "pub struct Engine;\n");
        write("crates/app/Cargo.toml", "[package]\nname = \"app\"\n[[bin]]\npath = \"main.rs\"\n");
        write("crates/app/main.rs", "mod flags;\nuse core_lib::Engine;\nstruct App { e: Engine }\n");
        write("crates/app/flags/defs.rs", "pub struct Verbose;\n");
        Map<String, TypeInfo> t = byFqn(analyze());
        assertEquals("core_lib", t.get("core_lib::Engine").module());
        assertEquals("app", t.get("app::App").module());
        assertEquals("flags::defs", t.get("app::flags::defs::Verbose").packageName());
        assertTrue(analyze().dependencies().get("app::App").contains("core_lib::Engine"), "dependency across crates");
    }
}
