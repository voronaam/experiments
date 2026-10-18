//! Serves the web viewer and the city model on localhost, and pushes rebuild notifications as server-sent events.
//!
//! Endpoints (all GET):
//! - `/`, `/index.html`, `/app.js`, `/walk.js`, `/replay.js`, `/style.css`, `/favicon.svg`: the embedded viewer ([`crate::assets`]);
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
            std::thread::spawn(move || handle(request, model, clients));
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

/// Routes one request; runs on its own thread. The URL is matched as-is, without percent-decoding.
fn handle(request: Request, model: Arc<CityModel>, clients: Arc<Mutex<Vec<Sender<Vec<u8>>>>>) {
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
/// one-line comment as its code, so the viewer can show it in place of the source.
fn source(request: Request, model: &CityModel, query: &str) {
    let id = query.split('&').find_map(|kv| kv.strip_prefix("id=")).and_then(|v| v.parse::<usize>().ok());
    let Some(b) = id.and_then(|i| model.buildings.get(i)) else {
        return send(request, 404, "text/plain", b"unknown type".to_vec());
    };
    let t = &model.types()[b.index];
    let code = match std::fs::metadata(&t.file) {
        Ok(m) if m.len() > MAX_SOURCE_BYTES => "// file too large to display".to_string(),
        _ => std::fs::read(&t.file)
            .map(|bytes| String::from_utf8_lossy(&bytes).into_owned())
            .unwrap_or_else(|e| format!("// unable to read {}: {e}", t.file.display())),
    };
    let mut w = JsonWriter::new();
    w.begin_object().field_str("file", &b.file).field_int("line", t.line as i64).field_int("endLine", t.end_line as i64).field_str("code", &code).end_object();
    send(request, 200, "application/json; charset=utf-8", w.finish().into_bytes());
}
