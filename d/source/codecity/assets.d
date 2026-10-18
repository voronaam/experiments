/// The web viewer, compiled in from the Java project's resources so every edition serves the identical viewer.
module codecity.assets;

enum indexHtml = import("index.html");
enum appJs = import("app.js");
enum walkJs = import("walk.js");
enum replayJs = import("replay.js");
enum styleCss = import("style.css");
enum faviconSvg = import("favicon.svg");

/// Content type and body for a request path; false when there is no such asset.
bool asset(string path, out string contentType, out string body_)
{
    switch (path)
    {
    case "/", "/index.html": contentType = "text/html; charset=utf-8"; body_ = indexHtml; return true;
    case "/app.js": contentType = "text/javascript; charset=utf-8"; body_ = appJs; return true;
    case "/walk.js": contentType = "text/javascript; charset=utf-8"; body_ = walkJs; return true;
    case "/replay.js": contentType = "text/javascript; charset=utf-8"; body_ = replayJs; return true;
    case "/style.css": contentType = "text/css; charset=utf-8"; body_ = styleCss; return true;
    case "/favicon.svg": contentType = "image/svg+xml"; body_ = faviconSvg; return true;
    default: return false;
    }
}
