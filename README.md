# CodeCity

An interactive 3D "software city" for Java code bases. It is a modern take on Richard Wettel's
[CodeCity](https://wettel.github.io/codecity.html), which was originally written in Smalltalk.

* **Packages become districts.** They are stacked platforms, one level per nesting depth. Chains like
  `com → acme → shop` are collapsed, and multi-module builds get one district per module.
* **Classes become buildings.**
  * Height = number of methods (switchable to LOC, complexity or attributes).
  * Footprint = number of attributes.
  * Colour = git churn, recency, author count, open pull requests, complexity, complexity per method, LOC, or type kind.

It is a Java 21 application. It parses sources with JavaParser, mines `git log`, and lays the city out with a
skyline bin-packer. The result is served to a WebGL viewer (Three.js) on localhost.

## Run

```bash
./mvnw package                       # builds target/codecity.jar (runs tests)
java -jar target/codecity.jar ~/src/my-project
java -jar target/codecity.jar https://github.com/google/gson   # clone and analyse a GitHub repo
java -jar target/codecity.jar --watch .          # live-updates while you edit
java -jar target/codecity.jar --export city.html # one self-contained, shareable file
```

| Option | |
|---|---|
| `-p, --port N` | viewer port (default 7070; the next free port is used if taken) |
| `-w, --watch` | re-analyse incrementally on file changes and push updates to the browser |
| `--no-tests` | leave test classes out |
| `--no-git` | skip git history mining |
| `--no-github` | no GitHub links or pull-request overlay |
| `--no-open` | don't launch a browser |
| `--export FILE` | write a standalone HTML file and exit |

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
| `analysis/JavaAnalyzer` | JavaParser (Java 25 syntax), parallel parsing, mtime cache. Computes NOM, NOA, LOC, WMC and dependencies resolved with Java's own name-lookup rules (no classpath needed). |
| `github/*` | URL parsing, clone cache, remote detection and the pull-request client (GraphQL with a token, REST without). |
| `analysis/GitHistory` | One `git log --name-only` pass that collects commits, authors and first/last change per file. |
| `layout/CityLayout` | Recursive skyline packing that tries several strip widths and keeps the most compact, square result. |
| `server/CityServer` | JDK `HttpServer` on loopback with virtual threads. Serves `/api/city`, `/api/source` (only files in the model) and `/api/events` (server-sent events). |
| `SourceWatcher` | `WatchService` with debouncing. A typical rebuild takes about 150 ms because unchanged files are not re-parsed. |
| `resources/web` | Three.js viewer. Instanced meshes keep it at a handful of draw calls for thousands of classes. GTAO, bloom, PCF soft shadows, and a shader patch for the night-time windows. |

The viewer loads Three.js, highlight.js and fonts from public CDNs, so the browser needs internet access.
