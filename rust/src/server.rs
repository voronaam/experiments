//! Serves the web viewer and the city model on localhost, and pushes rebuild notifications as server-sent events.

use crate::assets;
use crate::city::CityModel;
use crate::json::JsonWriter;
use std::io::Write;
use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, Mutex, RwLock};
use std::time::Duration;
use tiny_http::{Header, Request, Response, Server, StatusCode};

const MAX_SOURCE_BYTES: u64 = 4 * 1024 * 1024;

pub struct CityServer {
    server: Arc<Server>,
    model: Arc<RwLock<Arc<CityModel>>>,
    clients: Arc<Mutex<Vec<Sender<Vec<u8>>>>>,
    port: u16,
}

impl CityServer {
    pub fn bind(model: Arc<CityModel>, preferred: u16) -> std::io::Result<CityServer> {
        let mut last_err = None;
        for port in preferred..preferred.saturating_add(20) {
            match Server::http(("127.0.0.1", port)) {
                Ok(server) => {
                    return Ok(CityServer {
                        server: Arc::new(server),
                        model: Arc::new(RwLock::new(model)),
                        clients: Arc::new(Mutex::new(vec![])),
                        port,
                    })
                }
                Err(e) => last_err = Some(e),
            }
        }
        Err(std::io::Error::other(last_err.map(|e| e.to_string()).unwrap_or_default()))
    }

    pub fn url(&self) -> String {
        format!("http://localhost:{}/", self.port)
    }

    /// Handle that swaps in rebuilt models and notifies connected viewers.
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

fn broadcast(clients: &Mutex<Vec<Sender<Vec<u8>>>>, message: String) {
    clients.lock().unwrap().retain(|c| c.send(message.clone().into_bytes()).is_ok());
}

fn header(name: &str, value: &str) -> Header {
    Header::from_bytes(name.as_bytes(), value.as_bytes()).unwrap()
}

fn send(request: Request, status: u16, content_type: &str, body: Vec<u8>) {
    let response = Response::from_data(body)
        .with_status_code(StatusCode(status))
        .with_header(header("Content-Type", content_type))
        .with_header(header("Cache-Control", "no-store"));
    let _ = request.respond(response);
}

fn handle(request: Request, model: Arc<CityModel>, clients: Arc<Mutex<Vec<Sender<Vec<u8>>>>>) {
    let url = request.url().to_string();
    let (path, query) = url.split_once('?').unwrap_or((&url, ""));
    match path {
        "/api/city" => send(request, 200, "application/json; charset=utf-8", model.json().as_bytes().to_vec()),
        "/api/source" => source(request, &model, query),
        "/api/events" => {
            // tiny_http buffers streamed bodies, so write the event stream to the socket ourselves and flush per message
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
                    return; // browser went away; the next broadcast drops our sender
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
