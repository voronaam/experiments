package dev.codecity.analysis;

import dev.codecity.analysis.RustParse.Node;
import dev.codecity.analysis.RustParse.NodeKind;
import dev.codecity.analysis.RustParse.TokKind;
import dev.codecity.analysis.RustParse.Tree;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static dev.codecity.analysis.RustParse.NodeKind.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link RustParse}, ported from the Rust edition's {@code rust_parse.rs}. */
class RustParseTest {

    /** "KIND text" of every reference leaf, in node order. */
    private static List<String> leaves(String code) {
        Tree t = RustParse.parse(code);
        assertFalse(t.errors, "unexpected parse error in " + code);
        List<String> out = new ArrayList<>();
        for (Node n : t.nodes) {
            if (n.kind == TYPE_IDENT || n.kind == IDENT || n.kind == PATH) {
                out.add(n.kind + " " + t.rangeText(n.first, n.last));
            }
        }
        return out;
    }

    private static long count(String code, NodeKind kind) {
        return RustParse.parse(code).nodes.stream().filter(n -> n.kind == kind).count();
    }

    private static List<NodeKind> kinds(Tree t) {
        return t.nodes.stream().map(n -> n.kind).toList();
    }

    @Test
    void lexesRawStringsLifetimesCharsAndNestedComments() {
        String code = "r#\"a \" b\"# 'a 'b' '\\'' b'x' /* x /* y */ z */ r#type 1.0 1..2 x.0";
        RustParse.Lexed l = RustParse.lex(code);
        assertFalse(l.errors());
        List<TokKind> kinds = Arrays.stream(l.tokens()).map(t -> t.kind).toList();
        TokKind I = TokKind.IDENT, L = TokKind.LITERAL, P = TokKind.PUNCT, T = TokKind.LIFETIME;
        assertEquals(List.of(L, T, L, L, L, I, L, L, P, P, L, I, P, L), kinds);
        assertTrue(l.tokens()[5].commentBefore);
    }

    @Test
    void lexesLinesAndJointPunctuation() {
        RustParse.Lexed l = RustParse.lex("a\n\"x\ny\" ::\n: é");
        RustParse.Token[] t = l.tokens();
        assertEquals(1, t[0].line);
        assertEquals(2, t[1].line);
        assertEquals(3, t[1].endLine, "a string spanning lines ends on its last line");
        assertTrue(t[2].joint, "`::` is two joint colons");
        assertFalse(t[3].joint, "a colon on the next line is not joint");
        assertEquals(TokKind.IDENT, t[5].kind, "non-ASCII letters are identifiers");
    }

    @Test
    void classifiesNamesByPosition() {
        List<String> l = leaves("fn f(x: a::B<C>) { let y = Foo { z }; Vec::<T>::new(); bar::<U>(); <T as Tr>::f(); x.Method(); }");
        assertEquals(List.of("IDENT f", "IDENT x", "TYPE_IDENT C", "PATH a::B", "IDENT y", "TYPE_IDENT Foo", "IDENT z",
                "PATH Vec::<T>::new", "TYPE_IDENT T", "TYPE_IDENT U", "IDENT bar", "TYPE_IDENT T", "TYPE_IDENT Tr",
                "IDENT x"), l);
    }

    @Test
    void macroArgumentsAreIdentifiersExceptMetavariablesInRules() {
        List<String> l = leaves("fn f() { m!(Foo::bar, $Baz); macro_rules! r { ($T:ty) => { impl X for $T {} } } }");
        List<String> names = l.stream().map(s -> s.substring(s.indexOf(' ') + 1)).toList();
        assertEquals(List.of("f", "m", "Foo", "bar", "Baz", "r", "ty", "impl", "X", "for"), names);
    }

    @Test
    void countsDecisionPointsButNotLetChainsGuardsOrMacros() {
        String code = "fn f() { if a && b {} else if let Some(x) = y && z {} match q { A if c => 1, _ => 2 } "
                + "while x || y {} for i in 0.. {} loop {} assert!(a && b); }";
        assertEquals(2, count(code, IF));
        assertEquals(2, count(code, AND_OR));
        assertEquals(2, count(code, ARM));
        assertEquals(1, count(code, FOR));
    }

    @Test
    void structLiteralsAreNotAllowedInConditions() {
        assertFalse(RustParse.parse("fn f() { if x == foo { bar } }").errors);
        assertEquals(0, count("fn f() { if x == foo { bar } }", TYPE_IDENT));
    }

    @Test
    void blockLikeStatementsEndUnlessAMethodFollows() {
        assertFalse(RustParse.parse("fn f() { if a {} *b = 1; match x {}.foo(); m!{} m!(x) fn g() {} }").errors);
    }

    @Test
    void countsFieldsVariantsAndParameters() {
        String code = "struct S(pub A, #[x] B); enum E { A, B(u8), C { x: u8 } } "
                + "trait T { fn f(&self, self: Box<Self>, u32, _, a: u8); }";
        Tree t = RustParse.parse(code);
        assertFalse(t.errors);
        List<String> v = t.nodes.stream().filter(n -> n.kind == STRUCT || n.kind == ENUM || n.kind == FN)
                .map(n -> n.kind + "=" + n.value).toList();
        assertEquals(List.of("STRUCT=2", "ENUM=3", "FN=2"), v);
    }

    @Test
    void genericsCloseWithShiftTokens() {
        assertFalse(RustParse.parse("fn f() -> Vec<Vec<u8>> { let x: Vec<u8>= v; a >> b }").errors);
    }

    @Test
    void implHeadersKeepTheCoreType() {
        Tree t = RustParse.parse("impl<'a, T> !Send for &'a mut S<T> {}");
        Node ty = t.nodes.stream().filter(n -> n.kind == IMPL_TYPE).findFirst().orElseThrow();
        assertEquals("S", t.rangeText(ty.name, ty.value));
        Node tr = t.nodes.stream().filter(n -> n.kind == IMPL_TRAIT).findFirst().orElseThrow();
        assertEquals("Send", t.rangeText(tr.name, tr.value));
    }

    @Test
    void brokenInputIsFlaggedAndDoesNotThrow() {
        for (String code : List.of("fn f( {", "struct", "impl<", "}}}", "fn f() { let x = ; }", "'", "r#\"", "/* x", "#![")) {
            assertTrue(RustParse.parse(code).errors, code);
        }
    }

    /**
     * Deep nesting, nested {@code use} groups included, is cut off at {@link RustParse#MAX_DEPTH} as an error, without
     * exhausting the stack, even on a thread with a small (256 KiB) stack.
     */
    @Test
    void deepNestingIsAnErrorNotAStackOverflow() throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = new Thread(null, () -> {
            try {
                String parens = "(".repeat(10_000) + ")".repeat(10_000);
                assertTrue(RustParse.parse("fn f() { " + parens + " }").errors);
                assertTrue(RustParse.parse("mod a { ".repeat(5_000) + "}".repeat(5_000)).errors);
                assertTrue(RustParse.parse("fn f() { " + "-".repeat(50_000) + "x; }").errors);
                assertTrue(RustParse.parse("fn f() -> " + "&".repeat(50_000) + "T {}").errors);
                assertFalse(RustParse.parse("fn f() { let y = " + "#[a] ".repeat(50_000) + "x; }").errors,
                        "attributes before an expression are consumed without recursion");
                assertFalse(RustParse.parse("fn f() { if a {} " + "else if b {} ".repeat(50_000) + "}").errors,
                        "else-if chains are parsed iteratively");
                String uses = "use " + "{".repeat(200_000) + "}".repeat(200_000) + "; fn after() {}";
                Tree u = RustParse.parse(uses);
                assertTrue(u.errors);
                assertEquals(1, u.nodes.stream().filter(n -> n.kind == FN).count(), "the item after the use is parsed");
                int ok = RustParse.MAX_DEPTH - 1;
                assertFalse(RustParse.parse("use " + "{".repeat(ok) + "a" + "}".repeat(ok) + ";").errors);
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "small-stack", 256 * 1024);
        t.start();
        t.join();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    void useTreesKeepTheirStructure() {
        Tree t = RustParse.parse("use a::{self, b::C as D, e::*, {f}}; use ::g::h; use i::*;");
        assertFalse(t.errors);
        assertEquals(List.of(FILE, USE, USE_GROUP, USE_PREFIX, USE_PATH, USE_AS, USE_PATH, USE_GLOB, USE_PREFIX,
                USE_GROUP, USE_PATH, USE, USE_PATH, USE, USE_GLOB, USE_PREFIX), kinds(t));
    }

    @Test
    void commentsBetweenItemsAreRecorded() {
        Tree t = RustParse.parse("#[cfg(test)]\n// note\nmod tests {}\n");
        assertEquals(List.of(FILE, ATTR, IDENT, IDENT, COMMENT), kinds(t).subList(0, 5));
    }

    @Test
    void literalsThatLookLikeCodeAreSkipped() {
        String code = "fn f() { let s = r#\"} fn g() { \"#; let c = '{'; let b = b\"}\"; /* } /* { */ } */ "
                + "let l: &'static str = \"\\\"}\"; }";
        Tree t = RustParse.parse(code);
        assertFalse(t.errors);
        assertEquals(1, t.nodes.stream().filter(n -> n.kind == FN).count());
    }

    @Test
    void closuresRangesAndCastsParse() {
        for (String code : List.of(
                "fn f() { let a = |x: u8, (y, z)| -> u8 { x }; let b = move || a; v.iter().map(|&x| x as u32 + 1).sum::<u32>(); }",
                "fn f() { for i in 0..n {} let r = ..=5; let s = &x[1..]; if let 1..=9 | 20.. = n {} }",
                "fn f() { 'outer: loop { break 'outer; } let x = 'a: { break 'a 1 }; async move { x.await? }; }",
                "fn f() -> impl Fn(&str) -> Box<dyn Error + Send + 'static> { |_| todo!() }",
                "fn f<T: ?Sized + for<'a> Fn(&'a T)>(x: &T) where T: Iterator<Item = u8> {}",
                "fn f() { let Some(x) = y else { return }; let S { a, b: [c, ..], .. } = s; }",
                "const _: () = { impl X for Y {} }; static mut S: [u8; 4 * N] = [0; 4 * N];",
                "extern \"C\" { fn printf(f: *const c_char, ...) -> c_int; static errno: c_int; }")) {
            Tree t = RustParse.parse(code);
            assertFalse(t.errors, code + ": error at token " + t.firstError);
        }
    }

    /** Tiny deterministic PRNG (xorshift) so the fuzz test is reproducible. */
    private static long state = 0x9E3779B97F4A7C15L;

    private static long next() {
        state ^= state << 13;
        state ^= state >>> 7;
        state ^= state << 17;
        return state;
    }

    private static int below(int bound) {
        return (int) Long.remainderUnsigned(next(), bound);
    }

    /**
     * Mutates real source files (random deletions, insertions of syntax-relevant characters, truncations) and checks
     * that the parser and the analyser neither throw nor loop, and that the tree stays well-formed. The sources are the
     * Rust edition's parser and analyser (when present) and a built-in sample.
     */
    @Test
    void survivesMutatedSources() throws IOException {
        List<String> sources = new ArrayList<>();
        sources.add(SAMPLE);
        for (String name : List.of("rust_parse.rs", "rust_lang.rs", "text.rs")) {
            Path p = Path.of("rust/src/analysis", name);
            if (Files.isRegularFile(p)) {
                sources.add(Files.readString(p));
            }
        }
        String[] pieces = {"{", "}", "(", ")", "[", "]", "<", ">", "'", "\"", "#", "r#", "/*", "*/", "//", "::", "|",
                "&&", "$", "é", "\n", "fn ", "impl ", "match ", "if "};
        RustAnalyzer analyzer = new RustAnalyzer();
        Path dir = Path.of("rust/src/analysis").toAbsolutePath();
        for (String code : sources) {
            int[] chars = code.codePoints().toArray();
            for (int round = 0; round < 60; round++) {
                List<Integer> c = new ArrayList<>(Arrays.stream(chars).boxed().toList());
                int edits = 1 + below(8);
                for (int e = 0; e < edits && !c.isEmpty(); e++) {
                    int at = below(c.size());
                    switch (below(3)) {
                        case 0 -> c.subList(at, Math.min(at + 1 + below(20), c.size())).clear();
                        case 1 -> {
                            String p = pieces[below(pieces.length)];
                            c.addAll(at, p.codePoints().boxed().toList());
                        }
                        default -> c.subList(at, c.size()).clear();
                    }
                }
                StringBuilder b = new StringBuilder();
                c.forEach(b::appendCodePoint);
                String mutated = b.toString();
                Tree t = RustParse.parse(mutated);
                for (int i = 0; i < t.nodes.size(); i++) {
                    Node n = t.nodes.get(i);
                    assertTrue(n.end > i && n.end <= t.nodes.size());
                    assertTrue(n.first <= n.last || t.tokens.length == 0);
                }
                analyzer.parse(dir, dir.resolve("mutated.rs"), mutated);
            }
        }
    }

    /** A sample covering most of the grammar, so the fuzz test has material even without the Rust edition. */
    private static final String SAMPLE = """
            #![allow(dead_code)]
            use std::{collections::HashMap, fmt::{self, Display as D}, io::*};
            pub(crate) mod net { pub fn get<'a, T: Clone + 'a>(x: &'a T) -> Option<T> where T: Default { Some(x.clone()) } }
            #[derive(Debug)]
            pub struct Cart<T> { items: Vec<T>, pub owner: String }
            struct Pair(pub u8, i32);
            enum Shape { Circle { r: f64 }, Square(f64), Empty = 3 }
            trait Area: Sized { const N: usize; fn area(&self) -> f64; fn twice(&self) -> f64 { self.area() * 2.0 } }
            impl<T: D> Display for Cart<T> {
                fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                    for (i, x) in self.items.iter().enumerate() { if i > 0 && !f.alternate() { write!(f, ", ")?; } write!(f, "{x}")?; }
                    match self.items.len() { 0 => Ok(()), n if n > 3 => Err(fmt::Error), _ => { Ok(()) } }
                }
            }
            impl Area for Shape {
                const N: usize = 1;
                fn area(&self) -> f64 {
                    let v = match *self { Shape::Circle { r } => 3.14 * r * r, Shape::Square(s) => s * s, Shape::Empty => 0.0 };
                    'outer: while let Some(x) = it.next() { loop { if x > 1 || v < 0.0 { break 'outer; } continue; } }
                    let c = |a: f64, b| -> f64 { a + b }; let r = 0..=10; let p = <Vec<u8> as Default>::default();
                    unsafe { *ptr = 1 }; async move { fut.await }; v as f64
                }
            }
            macro_rules! m { ($x:expr) => { $x + 1 }; }
            static mut COUNT: u32 = 0;
            const LIMIT: [u8; 4] = [0; 4];
            extern "C" { fn abs(x: i32) -> i32; }
            #[cfg(test)]
            mod tests { use super::*; #[test] fn t() { assert_eq!(m!(1), 2); } }
            """;
}
