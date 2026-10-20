# CodeCity threat model

This document describes what CodeCity protects, who might attack it and how, and which controls stop them. It covers
all three editions (Java `src/`, Rust `rust/`, D `d/`) and all four viewers (browser, Java/JavaFX, Rust/egui and
D/ImGui desktop windows). The three editions behave identically, so unless a section says otherwise every statement
applies to each of them.

It reflects the state of the code after the security audit of October 2026. The audit findings and their fixes are
listed in [Audit findings](#audit-findings); the controls described below are the fixes.

## 1. What CodeCity does

CodeCity reads a source tree (Java, TypeScript/TSX or Rust), measures every type, mines `git log` and, optionally, the
open pull requests on GitHub, lays the result out as a 3D city and shows it in a browser page served on localhost, in
an exported standalone HTML file, or in a native desktop window. With `--history` it also reconstructs up to 500
past snapshots of the tree from git objects and analyses each of them.

```
                       ┌──────────────────────── CodeCity process (trusted) ─────────────────────────┐
 inspected tree ──read──▶ walker ─▶ in-house parsers ─▶ analysers ─▶ linker ─▶ layout ─▶ JSON writer │
 (untrusted)            │                                                          │                  │
 .git (untrusted) ─git──▶ hardened git runner ─▶ history / churn / remotes ────────┘                  │
 GitHub API ──https─────▶ PR client (token only to the repo's own API host)                           │
 (untrusted data)       │                                    JSON ─┬─▶ HTTP server (127.0.0.1) ──▶ browser viewer
                        │                                          ├─▶ HTML exporter ─▶ file ──────▶ browser viewer
                        │                                          └─▶ desktop viewer (JavaFX / egui / ImGui)
                        └─────────────────────────────────────────────────────────────────────────────┘
```

## 2. Assets

| Asset | Why it matters |
|---|---|
| The user's machine and account | Running code from an inspected repository would hand it over completely. |
| Files outside the inspected tree | Must not be read, written or deleted because of anything in the inspected tree. |
| GitHub tokens (`GH_TOKEN`, `GITHUB_TOKEN`, `GH_ENTERPRISE_TOKEN`, `gh auth token`) | Grant access to the user's repositories. |
| The inspected source code and its metadata | May be private; served on localhost and embedded in exports. |
| The user's browser session | Script running in the viewer's origin could read the local server. |
| The user's terminal | Escape sequences can rewrite what the user sees or, on some terminals, trigger actions. |
| Availability | A crafted repository must not hang or crash CodeCity or exhaust the machine. |

## 3. Attackers

1. **A hostile repository** — the main one. CodeCity is meant to be pointed at code the user does not trust yet. The
   attacker controls every file, file name, symlink and directory, the repository's `.git/config`, `.gitattributes`,
   `.gitmodules` and hooks (for a local directory), every git object (including trees that git itself would never
   check out, such as entries named `..`), commit messages, author names and e-mails, and the remote URL.
2. **A hostile GitHub account or API host** — controls pull-request titles, authors, URLs and file lists, and, if it can
   steer CodeCity to its own host, the whole API response.
3. **A hostile web page** open in the user's browser while CodeCity serves on localhost (DNS rebinding, cross-origin
   requests).
4. **The recipient of an exported HTML file** — not an attacker against the user, but the export must not run
   anything the city data smuggled in.
5. **Out of scope:** another local user or process on the same machine (can already read the user's files if
   permissions allow), a compromised JDK/Rust/D toolchain, git binary or OS, and the CDNs the browser viewer loads
   pinned libraries from (mitigated by Subresource Integrity, see §5.6).

## 4. Security invariants

These are the properties every change must preserve. Each is backed by tests in all three editions.

- **I1 — Inspected code is never executed.** No inspected file is ever compiled, evaluated, class-loaded, imported or
  interpreted. Parsing is done by CodeCity's own parsers, which are pure functions over a byte array. No build tool
  or package script (cargo, npm, node, mvn, gradle, rustc, javac) is ever started.
- **I2 — Repository configuration cannot run commands.** Every git command CodeCity runs on an analysed repository
  neutralises the configuration keys that make git start programs, and git transports are disabled.
- **I3 — Nothing is written outside CodeCity's own temporary directory** because of repository content, and nothing
  outside the inspected tree is read because of it (symlinks are never followed).
- **I4 — Tokens only go to the API host they belong to.** A token for github.com is only sent to api.github.com; a
  GitHub Enterprise token only to the host the user named in `GH_HOST`.
- **I5 — Untrusted text is always data.** It is never interpreted as HTML, script, a URL scheme other than `https:`,
  a printf format or a terminal escape sequence.
- **I6 — Bounded work.** Every recursive path is depth-capped, every input has a size cap, and no algorithm is worse
  than linear-times-a-constant-cap in the input.
- **I7 — The local server only answers the local viewer.** It binds to loopback and refuses requests whose `Host`
  is not localhost.

## 5. Threats and controls

### 5.1 Reading and parsing the tree (I1, I3, I6)

| Threat | Control |
|---|---|
| Inspected code gets executed | The only things CodeCity does with a source file are read its bytes and run its own lexer and parser over them (Java `*Parse.java`, Rust `analysis/*_parse.rs`, D `analysis/*_parse.d`). There is no third-party parser, no native parsing library, no class loader, reflection, `ScriptEngine`, `dlopen`, runtime `mixin`, `eval` or dynamic `import()` anywhere on that path. Manifests are read as text for one field: `[package] name` from `Cargo.toml` (hand-written linear scan) and only the existence of `package.json`; `tsconfig.json` is never read. |
| Symlinks lead out of the tree (e.g. `src -> /` or `secret.java -> ~/.ssh/id_rsa`) | The walkers never follow symlinks (Java `walkFileTree` without `FOLLOW_LINKS`, Rust `ignore` with `follow_links(false)`, D skips `isSymlink`). Files are re-checked with no-follow attributes when parsed, and manifests are only read if they are regular, non-symlink files. |
| FIFOs, devices or sockets block a read | Only regular files are opened. |
| Huge files, invalid UTF-8, NUL bytes | Files over `MAX_FILE_BYTES` (16 MiB) are counted as failed and never read; manifests are capped at `MAX_MANIFEST_BYTES` (1 MiB). Invalid UTF-8 decodes as Latin-1; names that are not valid UTF-8 are skipped. |
| Stack exhaustion from deep nesting | Every recursive path in every parser counts towards a depth cap (Java 300, TypeScript 400, Rust 200), including expressions, types, generics, calls, indexes, `use` groups, attributes and JSX. Past the cap the rest of the group is skipped and the file is flagged as having parse errors. Tests run deep inputs on 256 KiB stacks. |
| Exponential or quadratic blow-ups | Speculative parsing is memoised and switches off once a file has hit the depth cap. Long names are capped by `MAX_FQN_BYTES` (1024), qualified-name prefixes by `MAX_QUALIFIED_PREFIXES` (16), on-demand/same-name imports by `MAX_IMPORT_CANDIDATES` (64), Rust glob imports by `MAX_GLOB_IMPORTS` (64) and member-type nesting by `MAX_TYPE_NESTING` (16). No regular expression is applied to file contents in Java or D (Rust's `regex` crate is linear). A 232-case adversarial corpus runs in all three editions with time limits. |
| A bug crashes the whole run on one file | Per-file analysis is wrapped: Java catches `RuntimeException` and `StackOverflowError`, Rust catches panics (`catch_unwind`, the release profile unwinds), D catches `Throwable` except `OutOfMemoryError`. The file is counted as failed and the run continues. |

### 5.2 Git (I2, I3, I4)

CodeCity runs `git rev-parse`, `remote`, `branch`, `config`, `log`, `rev-list`, `ls-tree`, `diff-tree` and
`cat-file` on the analysed repository, and `clone`, `fetch` and `reset` in its own clone cache.

| Threat | Control |
|---|---|
| `.git/config` runs a program (`core.fsmonitor`, hooks, `log.showSignature` → `gpg.program`, `core.pager`, `diff.external`, textconv, filters, credential helpers, `core.sshCommand`, `remote.*.uploadpack`, `url.*.insteadOf` → `ext::`, `include`/`includeIf`) | Every command goes through one hardened builder per edition (Java `Git.command`, Rust `git::command`, D `gitArgs`/`gitEnv`). It passes `-c core.fsmonitor= -c core.hooksPath=/dev/null -c log.showSignature=false` and, except in the clone cache, `-c protocol.allow=never` plus `protocol.<x>.allow=never` for every transport (command-line `-c` beats every config file, including included ones, and git passes it on to its own children). The environment sets `GIT_TERMINAL_PROMPT=0` and `GIT_NO_LAZY_FETCH=1`; stdin is the null device. Keys the commands cannot reach anyway: the pager (stdout is never a terminal), external diff and textconv (no patches are requested), filters (nothing is checked out). A test fixture (`src/test/resources/hostile-repo.sh`, used by all three editions) sets about twenty such keys and all hooks to programs that leave a marker file, and asserts that none appears. |
| Lazy fetches in a partial clone reach an attacker-controlled remote | Fetching is only allowed when the repository's real path is inside CodeCity's own clone cache, whose configuration CodeCity wrote itself. Elsewhere, missing objects are treated as absent. |
| History replay writes outside its temp directory (tree entries named `../../x`, `/abs/x`, `.git/x`, `a\..\b`) | Every path from `ls-tree`/`diff-tree` must pass `safePath`: each `/`-separated component non-empty, not `.`, `..` or `.git` (any case), and free of `\`, `:` and NUL. Unsafe paths are neither written nor deleted. The mirror is a fresh directory created with mode 0700 under the system temp directory, never holds symlinks (symlink and submodule entries are skipped), and is removed afterwards without following links. Blobs over 16 MiB are skipped unread. |
| Option injection (`github:o/r@--upload-pack=…`, `/tree/-x`) | Branches are validated (no leading `-`, no control characters, spaces, `~^:?*[\` or `..`) and passed after `--`; owners, repositories and hosts must match strict character sets. All processes are started with an argument vector — never through a shell. |
| A `git` or `gh` binary planted in the current directory | Both are resolved once to an absolute path by searching only absolute `PATH` entries (with `PATHEXT` on Windows) and started by that path. |
| A process hangs | Short git commands and `gh auth token` have a 30 s timeout after which the process is killed. Long-running `git log` has none (see §6). |

### 5.3 GitHub (I4, I5)

| Threat | Control |
|---|---|
| A hostile remote URL (`https://github.evil.example/o/r`) collects the user's token | `GH_TOKEN`/`GITHUB_TOKEN` and the `gh` token for github.com are only ever sent to `api.github.com`. For any other host, only `GH_ENTERPRISE_TOKEN`/`GITHUB_ENTERPRISE_TOKEN` are used, and only when `GH_HOST` names that host; `gh auth token` runs without the token variables and with `--hostname=<host>`. Without such an opt-in the host is not contacted at all. |
| A redirect forwards the `Authorization` header | Java follows redirects by hand, only to `https` on the same host and port; ureq (Rust) and libcurl (D) strip credentials on cross-host redirects. TLS certificates are always verified. |
| Huge or deeply nested API responses | Bodies are capped at 10 MB and JSON nesting at 128 levels in all editions. |
| A pull-request URL with a `javascript:`, `file:` or custom scheme | PR URLs are kept only if they start with `https://<repository host>/` and contain no control characters or spaces; otherwise they are rebuilt from the repository URL and PR number. Viewers re-check links before use (§5.5). |

### 5.4 Local HTTP server (I7)

| Threat | Control |
|---|---|
| Another machine connects | The server binds to the loopback interface only; there is no option to change that. |
| DNS rebinding: a web page resolves its own domain to 127.0.0.1 and reads `/api/city` and `/api/source` as same-origin | Every request is refused with 403 unless `Host` is `localhost`, `127.0.0.1` or `[::1]` (optionally with CodeCity's port). |
| Cross-origin reads | No CORS headers are sent, all endpoints are side-effect-free GETs, and JSON responses are top-level objects, which cannot be included as a script. |
| Path traversal | Static assets come from a fixed whitelist compiled into the program; `/api/source` takes only an integer index into the analysed model, re-checks that the file is a regular non-symlink file, caps it at 4 MiB and never reveals absolute paths or OS error text. |

Accepted: any process on the same machine can query the server while it runs (no authentication), and there are no
connection limits — both require local access.

### 5.5 Viewers (I5)

| Threat | Control |
|---|---|
| Script injection through names, paths, commit subjects, author names or PR titles in the browser viewer | Untrusted text reaches the page only through `textContent` or the `esc()` helper; highlight.js escapes source text. There is no `eval`, `new Function`, string timer or `document.write`. |
| Script injection that slips past escaping | A Content-Security-Policy allows scripts only from the page's own origin, two pinned CDN paths and the page's own inline scripts, identified by SHA-256 hashes that the server and the exporters compute from the scripts they emit (tests assert they match). Inline event handlers, `javascript:` URLs, `eval`, plugins, `<base>` and forms are blocked. |
| `</script>`, `<!--` or U+2028 in data break out of the exported page's embedded JSON | The JSON writers escape `<`, `>`, `&`, U+2028, U+2029 and all control characters. |
| A tampered CDN library | highlight.js has a sha384 `integrity` attribute; the three.js import map has integrity hashes for all 18 modules it loads. |
| Links with dangerous schemes | The browser viewer turns every link that is not `https://` into `#` (`httpsUrl`); the desktop viewers refuse to open anything but `https://` links without spaces or control characters (`isWebLink`). `target="_blank"` links carry `rel="noopener"`, and the page sends no referrer. |
| printf-format injection in the D ImGui viewer (`%n`, `%s` in a file or type name) | Untrusted text is never a format string: tooltips use `"%s"`, coloured text uses `igTextUnformatted` with explicit begin/end pointers. |
| Desktop source window reads through a symlink or blocks on a FIFO | Same rule as `/api/source`: no-follow stat, regular files only, 4 MiB cap. |
| JavaFX / egui | Neither renders markup: JavaFX `Label`/`Text`/`Tooltip` and egui text are inert, and there is no WebView. |

### 5.6 Terminal (I5)

Every untrusted string the CLIs print (paths, error messages, remote URLs, commit subjects, network errors) goes through
`printable()`, which replaces C0, DEL and C1 control characters (except newline and tab) with `?`. The Java CLI prints
one sanitised `error:` line instead of a stack trace, like the other two editions.

## 6. Residual risks (accepted)

| Risk | Why accepted |
|---|---|
| A `git log` on a repository whose config includes a planted FIFO (`include.path`) can hang | Denial of service only, and the user can interrupt it. Short git commands time out after 30 s. |
| `mailmap.file`/`mailmap.blob` in repository config can read another local file | Only e-mail-to-e-mail mappings can surface, and only as author counts. |
| A 16 MiB pathological file can still take tens of seconds and on the order of 1 GB of memory | All work is linear in the input and bounded by the caps; this is the price of a 16 MiB file limit. |
| A file swapped for a symlink or FIFO between the check and the read | Requires a local attacker; git cannot create FIFOs. |
| Any local process can query the local server while it runs | Same as any local development server; local access is out of scope. |
| The browser viewer depends on cdnjs and jsDelivr being reachable | Integrity hashes stop tampered files from running; the desktop viewers have no such dependency. |
| The browser openers (`xdg-open`, `open`, `rundll32`) are started by bare name | They only ever receive CodeCity's own `http://127.0.0.1:<port>/` URL, and on Unix a bare name is only looked up on `PATH`. |
| A user's own partial clone loses missing blobs during `--history` | Deliberate: fetching from a remote named in untrusted configuration is not allowed outside the clone cache. |
| Rust and D cannot recover from a real stack overflow (it aborts the process) | Every recursive path is depth-capped and tested on 256 KiB stacks; Java additionally catches `StackOverflowError`. |

## 7. Rules for changes

- Never start a process through a shell. Start git only through the hardened builder of each edition, and any other
  program only by an absolute path from the `PATH` resolver.
- Never execute, evaluate or load anything derived from the inspected tree, and never add a dependency that does
  (template engines, script engines, plugin loaders, native parsers).
- New recursion in a parser must call `enter()`/`leave()`; new loops over untrusted input must be linear; new
  per-item collections need a documented cap.
- Every path built from repository content goes through `safePath` before it touches the file system; every file
  read goes through the no-follow, regular-file, size-capped helpers.
- Untrusted text goes into the browser page only via `textContent` or `esc()`, into URLs only via `httpsUrl`/
  `isWebLink`, into the D GUI only via `"%s"` or `igTextUnformatted`, and onto the terminal only via `printable()`.
- Make every change in all three editions identically and keep `parity.sh` (with and without `--history`) identical.

## Audit findings

The October 2026 audit found and fixed the following. All were fixed in every edition they applied to, with tests.

| ID | Severity | Finding |
|---|---|---|
| G1 | Critical | History replay wrote crafted tree entries (`../../x`, absolute paths) outside its temp directory. |
| G2 | Critical | Tokens were sent to any host containing "github" taken from the repository's remote URL. |
| G3 | High | Repository config could run commands through `git log` (`log.showSignature` → `gpg.program`) and lazy fetches (`uploadpack`, ssh, credential helpers). |
| FS-1 | High | DNS rebinding let a web page read the city model and analysed sources from the local server. |
| A1 | High | Rust-language parser recursion past the depth cap: a ~400 KB file crashed every edition. |
| G4–G9 | Medium | `gh` returned Enterprise tokens for any host; branch option injection; Java resent `Authorization` on cross-host redirects; unbounded API responses and JSON depth; unchecked PR URL schemes; lax host validation. |
| V1, V2 | Medium | Viewer links without scheme checks; untrusted text used as an ImGui printf format string (D). |
| FS-2 | Medium | Rust watcher followed symlinks out of the tree. |
| A2–A8 | Medium | Memory and time blow-ups from qualified names, long names, the Java `Cargo.toml` regex (stack overflow), line counting, TypeScript generic speculation, import resolution and nested member types. |
| G10–G16, FS-3–FS-7, V3–V7, A9–A11 | Low | Memory use for large blobs, temp directory permissions, missing `--`, stdin inheritance, field separators in commit data, non-UTF-8 names, size caps, no-follow reads in the server and desktop source windows, missing CSP/SRI, terminal escape sequences, quadratic corner cases, manifest symlinks; plus `git`/`gh` lookup in the current directory on Windows. |
