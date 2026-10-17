package dev.codecity.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeScriptAnalyzerTest {

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
    void extractsClassesInterfacesEnumsAndModules() throws IOException {
        write("src/shop/cart.ts", """
                import { Item } from './item';

                export abstract class Cart {
                  private items: Item[] = [];
                  constructor(private readonly owner: string) {}
                  onChange = (e: Event) => { if (e && this.items.length > 0) { return 1; } return 0; };
                  abstract total(): number;
                  add(item: Item, qty: number) {
                    for (const i of this.items) { if (i === item) { return; } }
                    this.items.push(qty > 1 ? item : item);
                  }
                }

                export enum Status { Open, Paid = 2 }
                interface Totals { net: number; gross: number; format(): string }
                export type Point = { x: number; y: number };

                export function discount(c: Cart): number { return c ? 1 : 0; }
                export const TAX = 0.2;
                """);
        write("src/shop/item.ts", "export interface Item { sku: string; price: number }\n");
        Analysis a = analyze();
        Map<String, TypeInfo> t = byFqn(a);

        TypeInfo cart = t.get("src/shop/cart.ts:Cart");
        assertEquals("abstract", cart.kind());
        assertEquals(Language.TYPESCRIPT, cart.language());
        assertEquals("src/shop", cart.packageName());
        assertEquals(2, cart.noa(), "field + constructor parameter property");
        assertEquals(4, cart.nom(), "constructor, arrow-function field, abstract method, add");
        assertEquals(3, cart.methods().stream().filter(m -> m.name().equals("onChange")).findFirst().orElseThrow().complexity());
        assertEquals(4, cart.methods().stream().filter(m -> m.name().equals("add")).findFirst().orElseThrow().complexity());

        assertEquals(2, t.get("src/shop/cart.ts:Status").noa());
        assertEquals("interface", t.get("src/shop/cart.ts:Totals").kind());
        assertEquals(1, t.get("src/shop/cart.ts:Totals").nom());
        assertEquals("type", t.get("src/shop/cart.ts:Point").kind());

        TypeInfo module = t.get("src/shop/cart.ts");
        assertEquals("module", module.kind());
        assertEquals("cart.ts", module.name());
        assertEquals(1, module.nom());
        assertEquals(1, module.noa());

        assertTrue(a.dependencies().get("src/shop/cart.ts:Cart").contains("src/shop/item.ts:Item"));
        assertTrue(a.dependencies().get("src/shop/cart.ts").contains("src/shop/cart.ts:Cart"));
    }

    @Test
    void resolvesIndexFilesAliasesDefaultExportsAndTsx() throws IOException {
        write("package.json", "{}");
        write("src/components/index.ts", "export default class Button {}\n");
        write("src/lib/db.ts", "export class Db {}\n");
        write("src/App.tsx", """
                import Button from './components';
                import { Db } from '@/lib/db';
                import React from 'react';
                export const App = () => <div><Button /></div>;
                class Store { db: Db; }
                """);
        write("src/App.test.tsx", "import { App } from './App';\ntest('x', () => { App(); });\n");
        Analysis a = analyze();
        Map<String, TypeInfo> t = byFqn(a);
        assertTrue(a.dependencies().get("src/App.tsx").contains("src/components/index.ts:Button"));
        assertTrue(a.dependencies().get("src/App.tsx:Store").contains("src/lib/db.ts:Db"));
        assertTrue(t.get("src/App.test.tsx").test());
        assertFalse(t.get("src/App.tsx").test());
        assertTrue(a.dependencies().get("src/App.test.tsx").contains("src/App.tsx"));
    }

    @Test
    void ignoresDeclarationFiles() throws IOException {
        write("types/global.d.ts", "interface Window { x: number }\n");
        assertTrue(analyze().types().isEmpty());
    }
}
