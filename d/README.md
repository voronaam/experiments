# CodeCity — D edition

A native D port of CodeCity with the same features as the [Java](../README.md) and [Rust](../rust/README.md)
editions:

* **Same CLI:** the same command-line flags.
* **Same JSON:** a byte-compatible city model; the city data is identical to the other editions.
* **Same browser viewer:** the web viewer files are compiled in from `../src/main/resources/web`.
* **Desktop window:** a native window built on OpenGL 3.3 + Dear ImGui.

```bash
dub build -b release              # → bin/codecity  (desktop window included)
dub build -b release -c cli       # CLI + browser viewer only; needs no X11/GL headers
./bin/codecity ~/src/project
./bin/codecity --gui --watch ~/src/project
./bin/codecity https://github.com/google/gson --export gson.html
dub test                          # unit tests
```

## Requirements

* **Compiler:** [LDC](https://github.com/ldc-developers/ldc/releases) ≥ 1.43, which includes `dub`.
* **Build tools:** a C/C++ compiler, `curl` and `tar` (`sudo apt install build-essential curl` on Debian/Ubuntu).
* **For the desktop window only:** the X11 development headers.
  `sudo apt install libx11-dev libxrandr-dev libxinerama-dev libxcursor-dev libxi-dev libxext-dev`.
  At runtime GLFW loads X11 and OpenGL dynamically.

The first build runs `scripts/build-native.sh`. It downloads pinned sources into `third_party/` (git-ignored) and
builds two static libraries:

| Library | Contents |
|---|---|
| `libcodecity_parsers.a` | tree-sitter 0.27.0, plus the Java 0.23.5, TypeScript/TSX 0.23.2 and Rust 0.24.2 grammars (the same versions the Rust edition uses). |
| `libcodecity_gui.a` | GLFW 3.4 (X11), and Dear ImGui 1.92.9 via cimgui. The script also fetches the Inter and JetBrains Mono fonts (SIL Open Font License), which are embedded in the binary. |

You can override the tools with `NATIVE_CC`, `NATIVE_CXX`, `AR` and `X11_INCLUDE`; `CC` sets the linker LDC uses.

## Performance

Best of three runs, analysing and exporting the whole city with git history:

| Code base | Java | Rust | D |
|---|---|---|---|
| google/gson — 721 Java types | 3.28 s · 188 MB | 0.35 s · 17 MB | 0.36 s · 23 MB |
| colinhacks/zod — 1,761 TypeScript types | 10.39 s · 198 MB | 0.69 s · 31 MB | 0.69 s · 45 MB |
| BurntSushi/ripgrep — 500 Rust types | 4.57 s · 178 MB | 0.27 s · 23 MB | 0.31 s · 27 MB |

How the D edition gets there:
* **Parsing:** files are parsed in parallel (`std.parallelism`), with tree-sitter called directly through C bindings
  and trees walked with a cursor.
* **Git:** `git log` runs on its own thread while the parsing happens.
* **Watch mode:** only files that changed are re-parsed, and changes are picked up via inotify on Linux (with a
  polling fallback elsewhere).

## Parity

On all three code bases above, the city data from the D edition is identical to the Java and Rust editions', compared
field by field: every building, metric, position, district and dependency edge.

## Desktop mode

`--gui` opens a GLFW window with its own OpenGL 3.3 renderer:

* every building and district is drawn instanced;
* soft shadows (PCF shadow map) and the night-time glow / lit-window shader;
* an ImGui interface: search, the inspector, a source viewer with syntax highlighting, legend, toasts and snapshots;
* dependency arcs with flowing particles, PR beacons, and live reload.

Settings are saved in `~/.config/codecity/desktop.json`, the same file and format as the Rust edition, so both share
them. Snapshots go to `~/Pictures` (or the current directory if that doesn't exist).

## Layout

| | |
|---|---|
| `source/app.d` | Command line. |
| `source/codecity/analysis/` | Source walker and cache (`package.d`), and the analysers `java.d`, `typescript.d`, `rustlang.d`. |
| `source/codecity/{layout,city,json}.d` | Skyline layout, the resolved city model, and its JSON form. |
| `source/codecity/{git,github}.d` | Git history; GitHub URLs, clone cache, permalinks and pull requests (via libcurl). |
| `source/codecity/{server,htmlexport,watch}.d` | Small HTTP/1.1 server with live-reload events, HTML export, and the file watcher. |
| `source/codecity/gui/` | GLFW/GL/ImGui bindings, renderer, interface, source highlighter, PNG writer. |
| `source/codecity/c/treesitter.d` | tree-sitter C API bindings. |
