# CodeCity

An interactive 3D "software city" for **Java, TypeScript and Rust** code bases. It is a modern take on Richard Wettel's
[CodeCity](https://wettel.github.io/codecity.html), which was originally written in Smalltalk.

* **Packages become districts.** They are stacked platforms, one level per nesting depth. Chains like
  `com → acme → shop` are collapsed, and multi-module builds get one district per module.
  * For TypeScript, districts are directories. For Rust, they are crates and modules.
* **Types become buildings.** These are classes, interfaces, enums, records, structs, traits and type aliases.
  Each file's free functions also form one "module" building.
  * Height = number of methods (switchable to LOC, complexity or attributes).
  * Footprint = number of attributes.
  * Colour = git churn, recency, author count, open pull requests, complexity, complexity per method, LOC, or type kind.

It is a Java 21 application. It parses Java with JavaParser and TypeScript/Rust with tree-sitter, mines `git log`, and lays the city out with a
skyline bin-packer. You can view the result in two ways:

* **Desktop window** (`--gui`): a native JavaFX 3D application. It needs no browser and no internet connection.
* **Browser viewer** (default): a WebGL page (Three.js) served on localhost, or exported as a single HTML file.

> **Native editions:** [`rust/`](rust/README.md) and [`d/`](d/README.md) are ports with the same CLI, the same
> output and the same viewers. They are 10–19× faster and use about a tenth of the memory.

## Run

```bash
./mvnw package                       # builds target/codecity.jar (runs tests)
java -jar target/codecity.jar --gui ~/src/my-project        # native desktop window
java -jar target/codecity.jar ~/src/my-project              # browser viewer
java -jar target/codecity.jar https://github.com/google/gson   # clone and analyse a GitHub repo
java -jar target/codecity.jar --watch .          # live-updates while you edit
java -jar target/codecity.jar --export city.html # one self-contained, shareable file
```

| Option | |
|---|---|
| `-g, --gui` | open a native desktop window instead of the browser viewer |
| `-p, --port N` | viewer port (default 7070; the next free port is used if taken) |
| `-w, --watch` | re-analyse incrementally on file changes and push updates to the browser |
| `--no-tests` | leave test classes out |
| `--no-git` | skip git history mining |
| `--no-github` | no GitHub links or pull-request overlay |
| `--no-open` | don't launch a browser |
| `--export FILE` | write a standalone HTML file and exit |

## Languages

Point CodeCity at any directory. Every supported file is analysed, and mixed-language repositories end up in one
city. The header shows the language mix.

| | Java | TypeScript (`.ts`, `.tsx`, `.mts`, `.cts`) | Rust (`.rs`) |
|---|---|---|---|
| Parser | JavaParser | tree-sitter (TypeScript / TSX grammars) | tree-sitter |
| Districts | packages; build modules (`…/src/main/java`) | directories; one module per `package.json` project | modules; one module per crate (`Cargo.toml`) |
| Buildings | classes, interfaces, enums, records, annotations | classes, interfaces, enums, type aliases + one **module** building per file | structs, enums, unions, traits + one **module** building per module |
| Methods | methods and constructors | methods, arrow-function fields, signatures | everything in `impl` blocks anywhere in the crate (trait impls included), trait methods |
| Attributes | fields, record components, enum constants | fields, constructor parameter properties, enum members | struct fields, tuple fields, enum variants |
| Dependencies | imports, same package, nested types | `import` bindings resolved to files: relative paths, `index` files, `.js` specifiers, `@/…` / `~/…` aliases | `use` trees (`crate::`, `super::`, `self::`, groups, aliases, globs), same-module names, other workspace crates |
| Test code | `src/test`, `*Test.java` | `*.test.ts`, `*.spec.ts`, `__tests__/`, `test/` | `#[cfg(test)]` modules (kept in their parent's district), `tests/`, `benches/` |

Complexity is McCabe complexity per function: branches, loops, `case`/`catch`/match arms, `?:`, `&&`, `||` and `??`.

* **TypeScript module buildings:** a file's top-level functions (including `const Foo = () => …` React components)
  and constants make up its module building. Top-level calls with a callback, such as `describe("…", () => …)` or
  `app.get("/", …)`, appear as methods, so test suites and route tables show up.
* **Skipped files:** `.d.ts` declaration files are ignored, as are `node_modules`, `dist`, `build` and `target`.

## Desktop mode (`--gui`)

The desktop window has the same features as the browser viewer:

* the same metrics and colour modes, with legend, search (`/`), and day/night (`N`)
* the inspector, dependency arcs, and source viewer (in its own window)
* GitHub links, PR colour mode and PR beacons
* live reload with `--watch`
* PNG snapshots

Your settings (metrics, theme, toggles) are remembered between runs.

Differences from the browser viewer:

* JavaFX 3D has no shadows, ambient occlusion or bloom. Night mode makes buildings glow in their own colours instead
  of lighting individual windows.
* GitHub links open in your default browser when you click them.
* The jar is built for the OS you build it on, because JavaFX ships native libraries per platform. Build on each OS
  you want to run it on; the browser mode works everywhere regardless.
* 3D needs a working GPU driver. If JavaFX falls back to its 2D-only software renderer, the window explains this.
  You can force 3D with `java -Dprism.forceGPU=true -jar target/codecity.jar --gui …`, which also works with software
  OpenGL such as Mesa llvmpipe.

## GitHub integration

* **Analyse any repository by URL.** Accepted forms are `https://github.com/owner/repo`,
  `…/tree/<branch>`, `git@github.com:owner/repo.git` and `github:owner/repo[@branch]`.
  * The repository is cloned blobless into `~/.cache/codecity/repos/` (or `$XDG_CACHE_HOME`). This keeps full history
    for the churn metrics while downloading only the current files.
  * Later runs fetch the latest changes into the same clone.
  * Private repositories work through your normal git credentials.
* **Permalinks.** A local checkout with a GitHub `origin` remote is detected automatically. The inspector then links each
  class to its **File** (with the class's lines highlighted), **Blame** and **History** on GitHub.
  * Links pin to the checked-out commit when it has been pushed, and to the branch name otherwise.
  * GitHub Enterprise hosts work too.
* **Open pull requests.** CodeCity loads the open PRs and the files each one changes.
  * Classes touched by a PR get a glowing beacon, and there is an *Open pull requests* colour mode.
  * The inspector lists the PRs touching the selected class, with author, age and draft status.
  * To load every open PR, set `GH_TOKEN` / `GITHUB_TOKEN` or log in with the `gh` CLI. This uses GraphQL, which can
    fetch up to 300 PRs in a few requests.
  * Without a token, the anonymous REST limit (60 requests/hour) allows only the ~30 most recently updated PRs.
  * The token is only ever sent to the repository's own GitHub API host.

## In the viewer

* Drag to orbit, right-drag to pan, scroll to zoom toward the cursor.
* Hover a building for its metrics. Click it to open the inspector, which shows methods ranked by complexity,
  what the class uses and what uses it, and a **Source** button with syntax highlighting.
* Selecting a class draws animated dependency arcs. Orange arcs go to the classes it uses, and blue arcs come from
  classes that use it. Everything unrelated fades out.
* `/` search · `F` frame the city · `N` day/night · `Esc` clear · double-click a district to zoom into it.
* Night mode lights the windows and makes hot spots glow (bloom). Ambient occlusion can be toggled.
* **Snapshot** saves a PNG.

## How it is built

| | |
|---|---|
| `analysis/SourceAnalyzer` | Walks the tree, parses files in parallel with the right `LanguageSupport` (cached by modification time, so watch-mode rebuilds re-parse only what changed), then links each language. |
| `analysis/JavaAnalyzer` | JavaParser (Java 25 syntax). Computes NOM, NOA, LOC, WMC and dependencies resolved with Java's own name-lookup rules (no classpath needed). |
| `analysis/TypeScriptAnalyzer`, `RustAnalyzer` | tree-sitter grammars (natives for Linux, macOS and Windows on x86-64 and ARM are bundled in the jar). They resolve imports and `use` paths, and Rust merges `impl` blocks into their types. |
| `github/*` | URL parsing, clone cache, remote detection and the pull-request client (GraphQL with a token, REST without). |
| `analysis/GitHistory` | One `git log --name-only` pass that collects commits, authors and first/last change per file. |
| `layout/CityLayout` | Recursive skyline packing that tries several strip widths and keeps the most compact, square result. |
| `server/CityServer` | JDK `HttpServer` on loopback with virtual threads. Serves `/api/city`, `/api/source` (only files in the model) and `/api/events` (server-sent events). |
| `SourceWatcher` | `WatchService` with debouncing. A typical rebuild takes about 150 ms because unchanged files are not re-parsed. |
| `desktop/*` | JavaFX viewer. All buildings form one `TriangleMesh` and are coloured through a palette texture, so recolouring never rebuilds geometry. Picking maps the hit face back to its building. |
| `resources/web` | Three.js viewer. Instanced meshes keep it at a handful of draw calls for thousands of classes. GTAO, bloom, PCF soft shadows, and a shader patch for the night-time windows. |

The browser viewer loads Three.js, highlight.js and fonts from public CDNs, so it needs internet access. The desktop mode does not.
