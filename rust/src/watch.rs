//! Watches the source tree and invokes a callback (debounced) whenever source files change.

use crate::analysis::{is_ignored_dir, language_of};
use notify::{EventKind, RecursiveMode, Watcher};
use std::path::{Path, PathBuf};
use std::sync::mpsc::channel;
use std::time::Duration;

const DEBOUNCE: Duration = Duration::from_millis(400);

/// Starts watching on a background thread; the returned watcher must be kept alive.
pub fn watch(root: &Path, on_change: impl Fn() + Send + 'static) -> notify::Result<notify::RecommendedWatcher> {
    let (tx, rx) = channel::<()>();
    let root_buf: PathBuf = root.to_path_buf();
    let mut watcher = notify::recommended_watcher(move |res: notify::Result<notify::Event>| {
        let Ok(event) = res else { return };
        if matches!(event.kind, EventKind::Access(_)) {
            return;
        }
        let relevant = event.paths.iter().any(|p| {
            let inside_ignored = p
                .strip_prefix(&root_buf)
                .map(|rel| rel.components().any(|c| is_ignored_dir(&c.as_os_str().to_string_lossy())))
                .unwrap_or(true);
            !inside_ignored && (language_of(p).is_some() || matches!(event.kind, EventKind::Remove(_)) || p.is_dir())
        });
        if relevant {
            let _ = tx.send(());
        }
    })?;
    watcher.watch(root, RecursiveMode::Recursive)?;
    std::thread::spawn(move || {
        while rx.recv().is_ok() {
            // debounce: wait until changes stop arriving for a moment
            while rx.recv_timeout(DEBOUNCE).is_ok() {}
            on_change();
        }
    });
    Ok(watcher)
}
