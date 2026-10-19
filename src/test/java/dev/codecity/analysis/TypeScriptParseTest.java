package dev.codecity.analysis;

import dev.codecity.analysis.TypeScriptParse.Field;
import dev.codecity.analysis.TypeScriptParse.NodeKind;
import dev.codecity.analysis.TypeScriptParse.TokKind;
import dev.codecity.analysis.TypeScriptParse.Tree;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static dev.codecity.analysis.TypeScriptParse.NodeKind.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests of the in-house TypeScript parser, ported from the Rust edition's {@code typescript_parse.rs}. */
class TypeScriptParseTest {

    private static Tree parseClean(String code, boolean tsx) {
        Tree t = TypeScriptParse.parse(code, tsx);
        assertFalse(t.hasError, () -> "unexpected syntax error at " + t.firstError + " in " + code);
        return t;
    }

    /** The tokens carrying {@code flag}, in source order. */
    private static List<String> flagged(String code, boolean tsx, int flag) {
        Tree t = parseClean(code, tsx);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < t.tokens.size; i++) {
            if ((t.flags(i) & flag) != 0) {
                out.add(t.tokenText(i));
            }
        }
        return out;
    }

    /** The identifiers flagged as references, in source order. */
    private static List<String> refs(String code, boolean tsx) {
        return flagged(code, tsx, TypeScriptParse.REF);
    }

    /** The tokens flagged as decision points, in source order. */
    private static List<String> decisions(String code, boolean tsx) {
        return flagged(code, tsx, TypeScriptParse.DECISION);
    }

    /** Kinds of the program's top-level statements. */
    private static List<NodeKind> top(String code, boolean tsx) {
        Tree t = parseClean(code, tsx);
        return Arrays.stream(t.children(t.root)).mapToObj(t::kind).toList();
    }

    @Test
    void regexVersusDivision() {
        assertEquals(List.of("a", "b", "c", "d"), refs("let a = b / c / d;", false));
        assertEquals(List.of("r", "s"), refs("let r = /[/'`]+/g.test(s);", false));
        assertEquals(List.of("x", "y"), refs("if (x) /a`b/.exec(y)", false));
        assertEquals(List.of("f", "a", "g"), refs("f(a)/2; g(/=/);", false));
    }

    @Test
    void templatesNestAndCloseAtTheRightBrace() {
        assertEquals(List.of("s", "b", "d", "g"), refs("const s = `a${b ? `c${d}` : { e }.e}f${g}`;", false));
        assertEquals(List.of("?"), decisions("const s = `${a ? 1 : 2}`;", false));
    }

    @Test
    void genericsVersusComparison() {
        assertEquals(List.of("f", "T", "x", "a", "b", "c", "d", "e"), refs("f<T>(x); a < b; c < d > (e);", false));
        assertEquals(List.of("m", "Map", "Array", "Set", "K"), refs("const m = new Map<string, Array<Set<K>>>();", false));
        assertEquals(List.of("x", "y", "z", "w", "v"), refs("x = y >> 2; z >>>= 1; w >= v;", false));
        assertEquals(List.of("g", "T", "x", "T", "x"), refs("const g = <T,>(x: T) => x;", true));
        assertEquals(List.of("g", "T", "x", "T", "T", "x"), refs("const g = <T>(x: T) => <T>x;", false));
    }

    @Test
    void arrowFunctionsVersusParentheses() {
        Tree t = parseClean("a = (b, c) => d; e = (f, g); h = async (i) => i; j = async x => x; k = (l): M => l;", false);
        long arrows = 0;
        for (int i = 0; i < t.nodeCount; i++) {
            if (t.kind(i) == ARROW) {
                arrows++;
            }
        }
        assertEquals(4, arrows);
        assertEquals(List.of("?"), decisions("x = a ? (b) : c;", false));
        assertEquals(List.of("?"), decisions("x = a ? (b): c => d : e;", false));
    }

    @Test
    void propertyNamesAndShorthandsAreNotReferences() {
        assertEquals(List.of("x", "b", "d", "e", "g", "i"),
                refs("x = { a: b, c, [d]: e, f() { return g.h; }, ...i };", false));
        assertEquals(List.of("c", "e", "f", "g"), refs("const { a, b: c, d = e, ...f } = g;", false));
        assertEquals(List.of("A", "C", "f"), refs("class A { b: C; d() {} #e = f; }", false));
        assertEquals(List.of("I", "B", "d", "E", "F"), refs("interface I { a: B; c(d: E): F }", false));
        assertEquals(List.of("E", "B"), refs("enum E { A = B.C, D }", false));
        assertEquals(List.of(), refs("lbl: for (;;) { break lbl; }", false));
        assertEquals(List.of("u"), refs("let u = undefined;", false));
    }

    @Test
    void typeReferences() {
        assertEquals(List.of("T", "A", "C", "D", "e", "G", "h", "I", "J"),
                refs("type T = A.B.C<D> | typeof e.f | keyof G | [h: I] | `${J}`;", false));
        assertEquals(List.of("T", "U", "U", "V", "W", "V"),
                refs("type T<U> = U extends infer V extends W ? V : never;", false));
        assertEquals(List.of("f", "x", "x", "Y"), refs("function f(x): x is Y { return true; }", false));
        assertEquals(List.of("a", "b", "d"), refs("let a: readonly typeof b.c[] = d;", false));
        assertEquals(List.of("a", "Array"), refs("let a: Array<typeof import('m')>;", false));
        assertEquals(List.of("M", "K", "T", "K", "T", "K"),
                refs("type M = { readonly [K in keyof T as `x${K}`]-?: T[K] };", false));
    }

    @Test
    void jsx() {
        String code = "const a = <A.B c=\"d\\\" e:f={g}><div>it's {h} // not a comment</div><I {...j} /></A.B>;";
        assertEquals(List.of("a", "A", "e", "f", "g", "div", "h", "div", "I", "j", "A"), refs(code, true));
        assertEquals(List.of("f", "my-el", "my-el"), refs("const f = <><my-el x-y=\"1\">a > b</my-el></>;", true));
    }

    @Test
    void decisionPoints() {
        String code = "if (a && b || c ?? d) {} else if (e) {} for (;;) {} for (x of y) {} while (z) {} do {} while (w);\n"
                + "switch (v) { case 1: break; default: } try {} catch {} x &&= y; let t: A extends B ? C : D; o?.p;";
        assertEquals(List.of("if", "&&", "||", "??", "if", "for", "for", "while", "do", "case", "catch"),
                decisions(code, false));
    }

    @Test
    void declarations() {
        String code = "import a, { b as c, type d } from 'm';\nimport * as n from 'n';\nimport x = require('x');\n"
                + "export default class {}\nexport declare const q: number;\n@dec export abstract class P {}\n"
                + "declare global { interface Window {} }\nnamespace A.B { export const c = 1; }\ndeclare module 'm' { }\n"
                + "export const enum E { A }\ntype T = { a: 1 };\nfunction f(): void;\nfunction f() {}\nlet type = 1;\n";
        assertEquals(List.of(IMPORT, IMPORT, IMPORT, EXPORT_DEFAULT, EXPORT, EXPORT, AMBIENT, MODULE, AMBIENT, EXPORT,
                TYPE_ALIAS, FUNCTION_SIG, FUNCTION_DECL, VAR_DECL), top(code, false));
        Tree t = TypeScriptParse.parse("class C { @d x = 1; @d m() {} static { } [k: string]: any; "
                + "constructor(private a, readonly b) {} o(): void; }", false);
        int body = t.field(t.children(t.root)[0], Field.BODY);
        int[] members = t.children(body);
        assertEquals(List.of(FIELD_DEF, DECORATOR, METHOD_DEF, STATIC_BLOCK, INDEX_SIG, METHOD_DEF, METHOD_SIG),
                Arrays.stream(members).mapToObj(t::kind).toList());
        // a field's span includes its decorators, a method's does not
        assertEquals("@d x = 1", t.text(members[0]));
        assertEquals("m() {}", t.text(members[2]));
    }

    @Test
    void lineSpans() {
        Tree t = TypeScriptParse.parse("\nconst x = 1 // c\n\nclass A {\n  m() {\n  }\n}\n", false);
        int[] kids = t.children(t.root);
        assertEquals(List.of(2, 2), List.of(t.firstLine(kids[0]), t.lastLine(kids[0])));
        assertEquals(List.of(4, 7), List.of(t.firstLine(kids[1]), t.lastLine(kids[1])));
    }

    @Test
    void nonAsciiTextAndOffsets() {
        // identifiers may contain any non-ASCII character; U+00A0 separates tokens; text is decoded from UTF-8
        Tree t = parseClean("const größe = ä;", false);
        assertEquals(List.of("größe", "ä"), refs("const größe = ä;", false));
        assertEquals("const größe = ä;", t.text(t.children(t.root)[0]));
    }

    /**
     * Runs {@code r} on a thread with a 512 KiB stack: half of the JVM's default 1 MiB (which is also what the
     * fork-join workers that parse files in parallel get), to leave a safety margin for the JIT's frame sizes.
     */
    private static void onSmallStack(Runnable r) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                r.run();
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "small-stack", 512 << 10);
        thread.start();
        thread.join();
        if (failure.get() instanceof Error e) {
            throw e;
        }
        if (failure.get() instanceof RuntimeException e) {
            throw e;
        }
    }

    @Test
    void deepNestingIsAnErrorNotACrash() throws InterruptedException {
        onSmallStack(() -> {
            // (prefix, opening part, closing part, suffix), nested 20 000 times and 100 times
            String[][] cases = {
                    {"", "(", ")", ""}, {"", "[", "]", ""}, {"", "{", "}", ""}, {"", "a = ", "", ""}, {"", "!", "", ""},
                    {"", "x ? ", " : y", ""}, {"", "2 ** ", "2", ""}, {"", "<div>", "</div>", ""},
                    {"type T = ", "A<", ">", ""}, {"", "`${", "}`", ""}, {"", "new ", "", ""}, {"", "if (x) ", "", ""},
                    {"", "(a) => ", "a", ""}, {"", "function f() {", "}", ""}, {"let ", "[", "]", " = y"},
                    {"type T = ", "keyof ", "", ""},
            };
            for (String[] c : cases) {
                String deep = c[0] + c[1].repeat(20_000) + "x" + c[2].repeat(20_000) + c[3];
                assertTrue(TypeScriptParse.parse(deep, true).hasError, c[1]);
                // modest nesting is fine
                String modest = c[0] + c[1].repeat(100) + "x" + c[2].repeat(100) + c[3];
                assertFalse(TypeScriptParse.parse(modest, true).hasError, c[1]);
            }
            String chain = "if (a) {}" + " else if (b) {}".repeat(20_000);
            assertFalse(TypeScriptParse.parse(chain, false).hasError);
        });
    }

    /** A sample with a bit of every construct, for the fuzz test. */
    private static final String CORPUS = """
            #!/usr/bin/env node
            import React, { useState as useS, type FC } from "react";
            import * as path from 'path';
            export * from "./a"; export { b as default } from './b';
            /** doc */
            @Component({ selector: "x" })
            export abstract class Store<T extends object = {}> extends Base<T> implements IStore, Other {
              private static readonly items: Map<string, T[]> = new Map();
              #secret = 1; declare x?: number; accessor y = 2;
              constructor(private readonly db: Db, public name = `n${1 + 2}`) { super(db); }
              @Input() get value(): T | undefined { return this.items?.get("a")?.[0]; }
              set value(v) { if (v && !this.x) this.x ??= 1; }
              abstract total(): number;
              async *gen<U>(...args: U[]): AsyncGenerator<U> { for await (const a of args) yield a; }
              onChange = (e: Event): void => { e.preventDefault(); };
            }
            namespace NS.Inner { export const enum E { A = 1 << 2, B = A | 1, "c" = 3 } }
            declare module "m" { export function f(x: number): string; }
            type Fn = <A>(a: A, ...rest: [b?: string, ...c: number[]]) => asserts a is NonNullable<A>;
            type M<T> = { -readonly [K in keyof T as `get${Capitalize<K & string>}`]+?: () => T[K] } & Record<string, unknown>;
            type C<T> = T extends (infer U)[] ? U : T extends Promise<infer V> ? V : never;
            interface I extends J, K.L<M> { (x: number): string; new (y: string): I; readonly [k: string]: any; m?<T>(): void; get g(): number }
            function overload(a: string): void;
            function overload(a: any) { label: switch (typeof a) { case "string": break label; default: return a as unknown as never; } }
            const re = /ab+c\\/[/]/gi, div = 4 / 2 / 1, tpl = tag`x${re}y`, n = 0x1F_n, f = 1.5e-3;
            let obj = { a, b: 2, [c]: 3, d() {}, get e() { return 1 }, async *f() {}, ...g };
            const App: FC<{ title: string }> = ({ title }) => <div className="app" onClick={() => setX(x => x + 1)}>{title} it's &amp; <></></div>;
            try { throw new Error("x"); } catch (e: unknown) { console.log(e satisfies unknown); } finally { }
            do x++; while (x < 10)
            for (let i = 0, j = 1; i < j; i++) for (const k in obj) if (k) continue; else break;
            export default function* () { yield* other(); }
            """;

    @Test
    void corpusParsesCleanly() {
        Tree t = TypeScriptParse.parse(CORPUS, true);
        assertFalse(t.hasError, () -> "syntax error at " + t.firstError + ": "
                + new String(CORPUS.getBytes(StandardCharsets.UTF_8), t.firstError,
                Math.min(40, CORPUS.length() - t.firstError), StandardCharsets.UTF_8));
    }

    /** Checks the structural invariants of a tree: children lie within their parent's token range, in order. */
    private static void checkTree(Tree t) {
        assertEquals(TokKind.EOF, t.tokens.kind[t.tokens.size - 1]);
        for (int i = 0; i < t.nodeCount; i++) {
            assertTrue(t.end[i] <= t.tokens.size);
            int prev = t.first[i];
            for (int c : t.children(i)) {
                assertTrue(t.first[c] >= prev && t.end[c] <= Math.max(t.end[i], t.first[i]), "child outside parent");
                prev = Math.max(t.end[c], t.first[c]);
            }
            t.text(i);
            t.firstLine(i);
            t.lastLine(i);
        }
    }

    @Test
    void fuzzedInputNeverThrows() throws InterruptedException {
        onSmallStack(() -> {
            // A deterministic linear congruential generator, so failures are reproducible.
            long[] seed = {0x2545F4914F6CDD1DL};
            java.util.function.IntUnaryOperator next = n -> {
                seed[0] = seed[0] * 6364136223846793005L + 1442695040888963407L;
                return (int) Long.remainderUnsigned(seed[0] >>> 33, Math.max(n, 1));
            };
            byte[] base = CORPUS.getBytes(StandardCharsets.UTF_8);
            byte[] alphabet = "(){}[]<>/`'\"$#@!?:;,.=+-*&|\\\n x0".getBytes(StandardCharsets.US_ASCII);
            for (int round = 0; round < 1500; round++) {
                List<Byte> bytes = new ArrayList<>();
                for (byte b : base) {
                    bytes.add(b);
                }
                for (int k = 1 + next.applyAsInt(8); k > 0; k--) {
                    int at = next.applyAsInt(bytes.size() + 1);
                    int op = next.applyAsInt(3);
                    if (op == 0 && !bytes.isEmpty() && at < bytes.size()) {
                        int len = 1 + Math.min(next.applyAsInt(20), bytes.size() - at - 1);
                        bytes.subList(at, at + len).clear();
                    } else if (op == 1) {
                        bytes.add(at, alphabet[next.applyAsInt(alphabet.length)]);
                    } else {
                        bytes.subList(at, bytes.size()).clear();
                    }
                }
                byte[] raw = new byte[bytes.size()];
                for (int i = 0; i < raw.length; i++) {
                    raw[i] = bytes.get(i);
                }
                // The analyser receives text decoded like SourceText.read: UTF-8, else Latin-1.
                String code = decode(raw);
                checkTree(TypeScriptParse.parse(code, round % 2 == 0));
            }
            // binary garbage
            StringBuilder garbage = new StringBuilder();
            for (int i = 0; i < 20_000; i++) {
                garbage.append((char) next.applyAsInt(256));
            }
            checkTree(TypeScriptParse.parse(garbage.toString(), true));
        });
    }

    private static String decode(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(raw, StandardCharsets.ISO_8859_1);
        }
    }
}
