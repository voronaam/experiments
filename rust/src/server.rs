//! Serves the web viewer and the city model on localhost, and pushes rebuild notifications as server-sent events.
//!
//! Endpoints (all GET):
//! - `/`, `/index.html`, `/app.js`, the modules it imports (`/walk.js`, `/replay.js`, … — [`crate::assets::MODULES`]),
//!   `/style.css`, `/favicon.svg`: the embedded viewer ([`crate::assets`]);
//! - `/api/city`: the city model as JSON ([`CityModel::json`], computed once per model and cached);
//! - `/api/source?id=N`: the source file of building `N` (see [`source`]);
//! - `/api/events`: a server-sent event stream; an `update` event tells the viewer to reload `/api/city`.
//!
//! Concurrency: `tiny_http` accepts connections on its own threads and hands requests to [`CityServer::run`], which
//! spawns one thread per request. The current model sits behind an `RwLock<Arc<_>>`: each request clones the `Arc`
//! when it is accepted, so a rebuild swapping in a new model never disturbs a request already running. Every open
//! event stream registers a channel `Sender` in `clients`; [`broadcast`] sends to all of them under the mutex.

use crate::assets;
use crate::city::CityModel;
use crate::json::JsonWriter;
use std::io::Write;
use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, Mutex, RwLock};
use std::time::Duration;
use tiny_http::{Header, Request, Response, Server, StatusCode};

/// Larger files are not sent to the source panel (4 MiB).
const MAX_SOURCE_BYTES: u64 = 4 * 1024 * 1024;

pub struct CityServer {
    server: Arc<Server>,
    /// The model being served; replaced as a whole by the [`CityServer::updater`].
    model: Arc<RwLock<Arc<CityModel>>>,
    /// One sender per open `/api/events` stream; the receiving end is that stream's request thread.
    clients: Arc<Mutex<Vec<Sender<Vec<u8>>>>>,
    /// The port actually bound, which may differ from the one asked for.
    port: u16,
}

impl CityServer {
    /// Binds to 127.0.0.1 (never to other interfaces: the server reads source files) on `preferred` or, if that is
    /// taken, the next free port among the following 19 (not beyond 65535); failing those, on any free port the OS
    /// picks, as does a `preferred` of 0. The error is that last bind's failure.
    pub fn bind(model: Arc<CityModel>, preferred: u16) -> std::io::Result<CityServer> {
        let server = (preferred..=preferred.saturating_add(19))
            .find_map(|port| Server::http(("127.0.0.1", port)).ok())
            .map_or_else(|| Server::http(("127.0.0.1", 0)), Ok)
            .map_err(std::io::Error::other)?;
        // Fill in index.html's script hashes now rather than on the first request.
        assets::served_index();
        // The port actually bound, which differs from `preferred` after a fallback or for port 0.
        let port = server.server_addr().to_ip().map_or(preferred, |a| a.port());
        Ok(CityServer { server: Arc::new(server), model: Arc::new(RwLock::new(model)), clients: Arc::new(Mutex::new(vec![])), port })
    }

    pub fn url(&self) -> String {
        format!("http://localhost:{}/", self.port)
    }

    /// Handle that swaps in rebuilt models and notifies connected viewers.
    ///
    /// The SSE message is `event: update` with a small JSON payload, terminated by the blank line that ends an
    /// event. The viewer only listens for the event name and then fetches `/api/city` itself.
    pub fn updater(&self) -> impl Fn(Arc<CityModel>) + Send + 'static {
        let model = self.model.clone();
        let clients = self.clients.clone();
        move |next: Arc<CityModel>| {
            let types = next.buildings.len();
            *model.write().unwrap() = next;
            broadcast(&clients, format!("event: update\ndata: {{\"types\":{types}}}\n\n"));
        }
    }

    /// Serves forever; each request gets its own thread (event streams stay open).
    pub fn run(&self) {
        let clients = self.clients.clone();
        // Keep-alive: a line starting with ':' is an SSE comment, ignored by the browser. It keeps idle connections
        // (and proxies) from timing out, and it is what notices viewers that have gone away (see the event stream in
        // handle).
        std::thread::spawn(move || loop {
            std::thread::sleep(Duration::from_secs(20));
            broadcast(&clients, ": ping\n\n".into());
        });
        for request in self.server.incoming_requests() {
            let model = self.model.read().unwrap().clone();
            let clients = self.clients.clone();
            let port = self.port;
            std::thread::spawn(move || handle(request, model, clients, port));
        }
    }
}

/// Sends `message` to every open event stream and forgets the ones whose stream thread has ended (send fails once
/// the receiver is dropped).
fn broadcast(clients: &Mutex<Vec<Sender<Vec<u8>>>>, message: String) {
    clients.lock().unwrap().retain(|c| c.send(message.clone().into_bytes()).is_ok());
}

/// Only called with constant ASCII names and values, so the unwrap cannot fail.
fn header(name: &str, value: &str) -> Header {
    Header::from_bytes(name.as_bytes(), value.as_bytes()).unwrap()
}

/// Answers with `body`. `no-store` makes the browser fetch fresh data after a rebuild instead of a cached copy.
/// Errors (the browser went away) are ignored.
fn send(request: Request, status: u16, content_type: &str, body: Vec<u8>) {
    let response = Response::from_data(body)
        .with_status_code(StatusCode(status))
        .with_header(header("Content-Type", content_type))
        .with_header(header("Cache-Control", "no-store"));
    let _ = request.respond(response);
}

/// True for a `Host` header naming this server by a loopback name: `localhost`, `127.0.0.1` or `[::1]`, either alone
/// or followed by `:port` (case-insensitive). Anything else, including a missing header, is what a DNS-rebinding page
/// would send (see the module comment). The Java and D editions apply the same rule.
pub fn is_allowed_host(host: Option<&str>, port: u16) -> bool {
    let Some(host) = host else { return false };
    let h = host.to_ascii_lowercase();
    let h = h.strip_suffix(&format!(":{port}")).unwrap_or(&h);
    matches!(h, "localhost" | "127.0.0.1" | "[::1]")
}

/// Routes one request; runs on its own thread. The URL is matched as-is, without percent-decoding. A request with a
/// foreign `Host` is refused first ([`is_allowed_host`]).
fn handle(request: Request, model: Arc<CityModel>, clients: Arc<Mutex<Vec<Sender<Vec<u8>>>>>, port: u16) {
    let host = request.headers().iter().find(|h| h.field.equiv("Host")).map(|h| h.value.as_str());
    if !is_allowed_host(host, port) {
        return send(request, 403, "text/plain", b"forbidden host".to_vec());
    }
    let url = request.url().to_string();
    let (path, query) = url.split_once('?').unwrap_or((&url, ""));
    match path {
        "/api/city" => send(request, 200, "application/json; charset=utf-8", model.json().as_bytes().to_vec()),
        "/api/source" => source(request, &model, query),
        "/api/events" => {
            // tiny_http buffers streamed bodies, so write the event stream to the socket ourselves and flush per
            // message. The response head is written by hand for the same reason. `retry: 2000` tells the browser's
            // EventSource to reconnect 2 s after the connection drops (e.g. when the server restarts). This thread then
            // lives as long as the stream, blocked on its channel.
            let (tx, rx) = channel::<Vec<u8>>();
            clients.lock().unwrap().push(tx);
            let mut out = request.into_writer();
            let head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n\
                        Cache-Control: no-cache\r\nConnection: close\r\n\r\nretry: 2000\n\n";
            if out.write_all(head.as_bytes()).and_then(|_| out.flush()).is_err() {
                return;
            }
            while let Ok(msg) = rx.recv() {
                if out.write_all(&msg).and_then(|_| out.flush()).is_err() {
                    return; // browser went away; returning drops rx, so the next broadcast drops our sender
                }
            }
        }
        _ => match assets::get(path) {
            Some((ct, body)) => send(request, 200, ct, body.as_bytes().to_vec()),
            None => send(request, 404, "text/plain", b"not found".to_vec()),
        },
    }
}

/// Only files that are part of the analysed model can be read — never arbitrary paths.
///
/// `id` is an index into the model's buildings, the same index the viewer got in `/api/city`. Since each request
/// holds the model it started with, an index from an older model can name a different building after a rebuild.
/// The answer is `{"file", "line", "endLine", "code"}`; a file that is too large or unreadable is answered with a
/// one-line comment as its code, so the viewer can show it in place of the source. The metadata is read without
/// following links, and only a regular file is read: should the file have been replaced by a symbolic link (to a file
/// outside the tree) or a FIFO since the analysis, it is reported as unreadable rather than followed or blocked on.
/// An error names the file by its path relative to the root, never the absolute path.
fn source(request: Request, model: &CityModel, query: &str) {
    // The first `id=` parameter decides. A missing or malformed id (anything but decimal digits, e.g. `-1`, `+1` or
    // `abc`) is a bad request; a well-formed one that names no building is not found. The Java and D editions answer
    // the same.
    let digits = |v: &&str| !v.is_empty() && v.bytes().all(|b| b.is_ascii_digit());
    let id = query.split('&').find_map(|kv| kv.strip_prefix("id=")).filter(digits).and_then(|v| v.parse::<usize>().ok());
    let Some(id) = id else {
        return send(request, 400, "text/plain", b"bad id".to_vec());
    };
    let Some(b) = model.buildings.get(id) else {
        return send(request, 404, "text/plain", b"unknown type".to_vec());
    };
    let t = &model.types()[b.index];
    let unreadable = || format!("// unable to read {}", b.file);
    let code = match std::fs::symlink_metadata(&t.file) {
        Ok(m) if !m.is_file() => unreadable(),
        Ok(m) if m.len() > MAX_SOURCE_BYTES => "// file too large to display".to_string(),
        Ok(_) => std::fs::read(&t.file).map(|bytes| String::from_utf8_lossy(&bytes).into_owned()).unwrap_or_else(|_| unreadable()),
        Err(_) => unreadable(),
    };
    let mut w = JsonWriter::new();
    w.begin_object().field_str("file", &b.file).field_int("line", t.line as i64).field_int("endLine", t.end_line as i64).field_str("code", &code).end_object();
    send(request, 200, "application/json; charset=utf-8", w.finish().into_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Read;

    #[test]
    fn only_loopback_host_names_are_allowed() {
        for ok in ["localhost", "localhost:8080", "LOCALHOST:8080", "127.0.0.1:8080", "[::1]:8080", "[::1]"] {
            assert!(is_allowed_host(Some(ok), 8080), "{ok}");
        }
        for bad in ["", "evil.example", "evil.example:8080", "localhost:8081", "localhost.:8080", "127.0.0.2:8080",
            "localhost.evil.example:8080", "localhost:8080:8080", "0.0.0.0:8080"] {
            assert!(!is_allowed_host(Some(bad), 8080), "{bad}");
        }
        assert!(!is_allowed_host(None, 8080));
    }

    /// Sends a raw GET (so the Host header is fully controlled; None leaves it out) and returns the whole response.
    fn get(port: u16, path: &str, host: Option<&str>) -> String {
        let mut s = std::net::TcpStream::connect(("127.0.0.1", port)).unwrap();
        // A stuck server makes the read fail after this long instead of hanging the test run.
        s.set_read_timeout(Some(Duration::from_secs(20))).unwrap();
        let host = host.map(|h| format!("Host: {h}\r\n")).unwrap_or_default();
        s.write_all(format!("GET {path} HTTP/1.1\r\n{host}Connection: close\r\n\r\n").as_bytes()).unwrap();
        let mut out = String::new();
        let _ = s.read_to_string(&mut out);
        out
    }

    /// A DNS-rebinding request is refused; the source endpoint does not follow a link swapped in after analysis.
    #[cfg(unix)]
    #[test]
    fn refuses_foreign_hosts_and_does_not_follow_links() {
        let tmp = tempfile::tempdir().unwrap();
        let tmp = std::fs::canonicalize(tmp.path()).unwrap();
        let root = tmp.join("root");
        std::fs::create_dir_all(&root).unwrap();
        std::fs::write(root.join("A.java"), "class A {}").unwrap();
        std::fs::write(tmp.join("secret.txt"), "TOP SECRET").unwrap();
        let model = CityModel::build(&crate::analysis::SourceAnalyzer::new(), &root, true, false, None, None);
        let server = Arc::new(CityServer::bind(Arc::new(model), 0).unwrap());
        let port = server.port;
        let s = server.clone();
        std::thread::spawn(move || s.run());
        assert!(get(port, "/api/city", Some(&format!("evil.example:{port}"))).starts_with("HTTP/1.1 403"));
        assert!(get(port, "/", Some(&format!("evil.example:{port}"))).starts_with("HTTP/1.1 403"));
        assert!(!get(port, "/api/source?id=0", None).starts_with("HTTP/1.1 200"));
        assert!(get(port, "/api/city", Some(&format!("localhost:{port}"))).starts_with("HTTP/1.1 200"));
        assert!(get(port, "/api/source?id=0", Some(&format!("127.0.0.1:{port}"))).contains("class A {}"));

        std::fs::remove_file(root.join("A.java")).unwrap();
        std::os::unix::fs::symlink(tmp.join("secret.txt"), root.join("A.java")).unwrap();
        let r = get(port, "/api/source?id=0", Some(&format!("localhost:{port}")));
        assert!(!r.contains("TOP SECRET"), "{r}");
        assert!(r.contains("unable to read A.java"), "{r}");
        assert!(!r.contains(tmp.to_str().unwrap()), "{r}");
    }

    /// Analyses `root` without git or GitHub and serves it on a port the system picks; returns the port.
    fn serve(root: &std::path::Path) -> (Arc<CityServer>, u16) {
        let model = CityModel::build(&crate::analysis::SourceAnalyzer::new(), &std::fs::canonicalize(root).unwrap(), true, false, None, None);
        let server = Arc::new(CityServer::bind(Arc::new(model), 0).unwrap());
        let s = server.clone();
        std::thread::spawn(move || s.run());
        let port = server.port;
        (server, port)
    }

    /// The value of header `name` in a raw response (names compared case-insensitively).
    fn header<'a>(response: &'a str, name: &str) -> Option<&'a str> {
        response.split("\r\n").take_while(|l| !l.is_empty()).skip(1).find_map(|l| {
            let (n, v) = l.split_once(':')?;
            n.trim().eq_ignore_ascii_case(name).then(|| v.trim())
        })
    }

    /// The body of a raw response (everything after the blank line).
    fn body(response: &str) -> &str {
        response.split_once("\r\n\r\n").map_or("", |(_, b)| b)
    }

    /// The viewer's files are served with their content types and `no-store`; the page carries its
    /// Content-Security-Policy with the inline scripts' hashes filled in. Paths that are not viewer files or endpoints,
    /// including paths below an endpoint and paths that try to leave the asset list, are 404s.
    #[test]
    fn serves_the_viewer_files_with_their_types_and_nothing_else() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::write(tmp.path().join("A.java"), "class A {}").unwrap();
        let (_server, port) = serve(tmp.path());
        let host = format!("localhost:{port}");
        let host = Some(host.as_str());
        for path in ["/", "/index.html"] {
            let r = get(port, path, host);
            assert!(r.starts_with("HTTP/1.1 200"), "{r}");
            assert_eq!(header(&r, "Content-Type"), Some("text/html; charset=utf-8"));
            assert_eq!(header(&r, "Cache-Control"), Some("no-store"));
            assert!(r.contains(r#"<meta http-equiv="Content-Security-Policy""#), "{path}");
            assert!(r.contains("'sha256-"), "{path}");
            assert!(!r.contains("{{inline-script-hashes}}"), "{path}");
        }
        assert_eq!(header(&get(port, "/app.js", host), "Content-Type"), Some("text/javascript; charset=utf-8"));
        assert_eq!(header(&get(port, "/style.css", host), "Content-Type"), Some("text/css; charset=utf-8"));
        assert_eq!(header(&get(port, "/favicon.svg", host), "Content-Type"), Some("image/svg+xml"));
        let city = get(port, "/api/city", host);
        assert_eq!(header(&city, "Content-Type"), Some("application/json; charset=utf-8"));
        assert_eq!(header(&city, "Cache-Control"), Some("no-store"));
        assert!(body(&city).starts_with('{') && body(&city).contains("\"A\""), "{city}");
        for path in ["/nope", "/web/app.js", "/../pom.xml", "/index.htm", "/api", "/api/", "/api/city/x", "/api/source/x",
            "/api/events/x", "/api/cities"] {
            let r = get(port, path, host);
            assert!(r.starts_with("HTTP/1.1 404"), "{path}: {r}");
            assert_eq!(header(&r, "Content-Type"), Some("text/plain"), "{path}");
            assert_eq!(body(&r), "not found", "{path}");
        }
    }

    /// `/api/source`: a missing or malformed id is a 400, a well-formed id that names no building a 404; neither answer
    /// mentions a path. A valid id answers the file (relative), the type's line range and the code.
    #[test]
    fn source_answers_bad_and_unknown_ids() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(tmp.path().join("p")).unwrap();
        std::fs::write(tmp.path().join("p/A.java"), "package p;\n\nclass A {\n}\n").unwrap();
        let (_server, port) = serve(tmp.path());
        let host = format!("localhost:{port}");
        let host = Some(host.as_str());
        for query in ["", "?", "?id=", "?id=abc", "?id=-1", "?id=+0", "?id=0x1", "?id=1.0", "?x=1", "?id=99999999999999999999999"] {
            let r = get(port, &format!("/api/source{query}"), host);
            assert!(r.starts_with("HTTP/1.1 400"), "{query}: {r}");
            assert_eq!(body(&r), "bad id", "{query}");
        }
        for query in ["?id=1", "?id=999", "?id=4294967296"] {
            let r = get(port, &format!("/api/source{query}"), host);
            assert!(r.starts_with("HTTP/1.1 404"), "{query}: {r}");
            assert_eq!(body(&r), "unknown type", "{query}");
        }
        let r = get(port, "/api/source?x=1&id=0", host);
        assert!(r.starts_with("HTTP/1.1 200"), "{r}");
        assert_eq!(header(&r, "Content-Type"), Some("application/json; charset=utf-8"));
        assert_eq!(body(&r), r#"{"file":"p/A.java","line":3,"endLine":4,"code":"package p;\n\nclass A {\n}\n"}"#);
    }

    /// Files over 4 MiB are not sent (a placeholder comment is), a file of exactly 4 MiB is; a FIFO swapped in after
    /// the analysis is reported as unreadable without blocking the request.
    #[cfg(unix)]
    #[test]
    fn source_caps_large_files_and_never_blocks_on_a_fifo() {
        let tmp = tempfile::tempdir().unwrap();
        let a = tmp.path().join("A.java");
        std::fs::write(&a, "class A {}").unwrap();
        let (_server, port) = serve(tmp.path());
        let host = format!("localhost:{port}");
        let host = Some(host.as_str());
        std::fs::OpenOptions::new().write(true).open(&a).unwrap().set_len(MAX_SOURCE_BYTES + 1).unwrap();
        assert!(get(port, "/api/source?id=0", host).contains(r#""code":"// file too large to display""#));
        std::fs::write(&a, "x".repeat(MAX_SOURCE_BYTES as usize)).unwrap();
        let r = get(port, "/api/source?id=0", host);
        assert!(r.contains(r#""code":"xxxx"#), "{}", r.chars().take(300).collect::<String>());
        assert!(!r.contains("too large"));

        std::fs::remove_file(&a).unwrap();
        assert!(std::process::Command::new("mkfifo").arg(&a).status().unwrap().success());
        let (tx, rx) = channel();
        let h = host.unwrap().to_string();
        std::thread::spawn(move || tx.send(get(port, "/api/source?id=0", Some(&h))).unwrap());
        let r = rx.recv_timeout(Duration::from_secs(10)).expect("the request blocked on the FIFO");
        assert!(r.contains(r#""code":"// unable to read A.java""#), "{r}");
    }

    /// Reads from `s` until what was read ends with `marker`; fails at the end of the stream.
    fn read_until(s: &mut std::net::TcpStream, marker: &str) -> String {
        let mut got = Vec::new();
        let mut byte = [0u8];
        while !got.ends_with(marker.as_bytes()) {
            assert_eq!(s.read(&mut byte).unwrap(), 1, "stream ended before {marker:?}: {}", String::from_utf8_lossy(&got));
            got.push(byte[0]);
        }
        String::from_utf8(got).unwrap()
    }

    /// `/api/events`: a stream opens with the event-stream headers and a `retry` line; swapping in a new model sends
    /// one `update` event with the new type count, after which `/api/city` serves the new model. A foreign host gets
    /// no stream.
    #[test]
    fn events_announce_a_model_swap() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::write(tmp.path().join("A.java"), "class A {}").unwrap();
        let (server, port) = serve(tmp.path());
        let host = format!("localhost:{port}");
        assert!(get(port, "/api/events", Some(&format!("evil.example:{port}"))).starts_with("HTTP/1.1 403"));
        let mut s = std::net::TcpStream::connect(("127.0.0.1", port)).unwrap();
        s.set_read_timeout(Some(Duration::from_secs(20))).unwrap();
        s.write_all(format!("GET /api/events HTTP/1.1\r\nHost: {host}\r\n\r\n").as_bytes()).unwrap();
        let head = read_until(&mut s, "retry: 2000\n\n");
        assert!(head.starts_with("HTTP/1.1 200"), "{head}");
        assert_eq!(header(&head, "Content-Type"), Some("text/event-stream; charset=utf-8"));
        assert_eq!(header(&head, "Cache-Control"), Some("no-cache"));

        std::fs::write(tmp.path().join("Brand.java"), "class Brand {}").unwrap();
        let root = std::fs::canonicalize(tmp.path()).unwrap();
        let next = CityModel::build(&crate::analysis::SourceAnalyzer::new(), &root, true, false, None, None);
        server.updater()(Arc::new(next));
        read_until(&mut s, "event: update\ndata: {\"types\":2}\n\n");
        assert!(get(port, "/api/city", Some(&host)).contains("\"Brand\""));
    }

    /// When the preferred port is taken, the server binds one of the next ones (the Java and D editions search the
    /// same 20 ports) and reports the port it got in its URL; port 0 lets the system choose.
    #[test]
    fn falls_back_to_the_next_free_port() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::write(tmp.path().join("A.java"), "class A {}").unwrap();
        let taken = std::net::TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let busy = taken.local_addr().unwrap().port();
        let model = CityModel::build(&crate::analysis::SourceAnalyzer::new(), tmp.path(), true, false, None, None);
        let server = Arc::new(CityServer::bind(Arc::new(model), busy).unwrap());
        let port = server.port;
        assert_eq!(server.url(), format!("http://localhost:{port}/"));
        assert_ne!(port, busy, "bound the busy port");
        if busy <= 65535 - 19 {
            assert!(port > busy && port <= busy + 19, "{busy} -> {port}");
        }
        let s = server.clone();
        std::thread::spawn(move || s.run());
        assert!(get(port, "/api/city", Some(&format!("localhost:{port}"))).starts_with("HTTP/1.1 200"));
    }
}
