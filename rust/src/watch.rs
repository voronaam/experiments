//! Watches the source tree and invokes a callback (debounced) whenever source files change.
//!
//! Used by `--watch`: the callback rebuilds the city model (see `main.rs`), and the analyser's cache keeps that cheap.
//!
//! Threads: `notify` calls the event handler on its own thread; the handler only filters events and sends a unit
//! message over a channel. A separate thread spawned here receives those messages, debounces them and runs the
//! callback, so a slow rebuild never blocks event delivery.

use crate::analysis::{is_ignored_dir, language_of};
use notify::{EventKind, RecursiveMode, Watcher};
use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::mpsc::channel;
use std::time::Duration;

/// How long the tree must be quiet before the callback runs. Saving a file, a `git checkout` or a formatter run
/// produces a burst of events; they collapse into one rebuild.
const DEBOUNCE: Duration = Duration::from_millis(400);

/// Starts watching on a background thread; the returned watcher must be kept alive.
///
/// Dropping the watcher drops the handler and with it the channel's sender, so `recv` fails and the debounce thread
/// ends.
pub fn watch(root: &Path, on_change: impl Fn() + Send + 'static) -> notify::Result<notify::RecommendedWatcher> {
    let (tx, rx) = channel::<()>();
    let root_buf: PathBuf = root.to_path_buf();
    // Every directory seen under root. A removal or move-out event does not say whether the path was a directory,
    // and the path can no longer be inspected, so this tells the two apart.
    let mut dirs = HashSet::new();
    add_dirs(&mut dirs, root);
    // notify follows symbolic links by default, so a link to a directory (`ln -s / x` in the tree) would make it watch
    // everything below the target, possibly the whole file system, exhausting the inotify watches. The walker does
    // not follow links either, so nothing behind one can matter.
    let handler = move |res: notify::Result<notify::Event>| {
        let Ok(event) = res else { return };
        // Reads (including the analyser's own) are not changes; reacting to them could rebuild in a loop.
        if matches!(event.kind, EventKind::Access(_)) {
            return;
        }
        // An event matters if the kernel's queue overflowed (events were lost, so assume a change), or if any of its
        // paths is outside the ignored directories (same rule as the walker: build output, VCS and hidden
        // directories) and is a source file, a `Cargo.toml`/`package.json` (they decide crate and module names) or a
        // directory: an existing one (e.g. one moved in with sources inside,
        // which is then remembered with its sub-directories) or a known one that is gone (deleted or moved out; it
        // could have held sources). Changes to other files are ignored. Paths that are not under root count as
        // ignored. A symbolic link to a directory does not count as a directory (`symlink_metadata` does not follow
        // it): it could point anywhere, even at `/`, and `add_dirs` would then walk the target.
        let relevant = event.need_rescan()
            || event.paths.iter().any(|p| {
                let inside_ignored = p
                    .strip_prefix(&root_buf)
                    .map(|rel| rel.components().any(|c| is_ignored_dir(&c.as_os_str().to_string_lossy())))
                    .unwrap_or(true);
                if inside_ignored {
                    false
                } else if std::fs::symlink_metadata(p).is_ok_and(|m| m.is_dir()) {
                    add_dirs(&mut dirs, p);
                    true
                } else {
                    let manifest = p.file_name().is_some_and(|n| n == "Cargo.toml" || n == "package.json");
                    language_of(p).is_some() || manifest || dirs.remove(p)
                }
            });
        if relevant {
            let _ = tx.send(());
        }
    };
    let mut watcher = notify::RecommendedWatcher::new(handler, notify::Config::default().with_follow_symlinks(false))?;
    watcher.watch(root, RecursiveMode::Recursive)?;
    std::thread::spawn(move || {
        while rx.recv().is_ok() {
            // Debounce: wait until changes stop arriving for a moment. Events that arrive while on_change runs stay
            // queued, so they trigger one more rebuild afterwards rather than being lost.
            while rx.recv_timeout(DEBOUNCE).is_ok() {}
            on_change();
        }
    });
    Ok(watcher)
}

/// Remembers `dir` and, if it was not known yet, every directory below it that is not ignored. Symbolic links are not
/// followed, as in the walker (`DirEntry::file_type` does not follow them, and the caller checks `dir` itself).
fn add_dirs(dirs: &mut HashSet<PathBuf>, dir: &Path) {
    if !dirs.insert(dir.to_path_buf()) {
        return;
    }
    for entry in std::fs::read_dir(dir).into_iter().flatten().flatten() {
        if entry.file_type().is_ok_and(|t| t.is_dir()) && !is_ignored_dir(&entry.file_name().to_string_lossy()) {
            add_dirs(dirs, &entry.path());
        }
    }
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;

    /// A change behind a symbolic link to a directory outside the tree is not watched (the walker does not follow
    /// links either), while a change inside the tree is.
    #[test]
    fn does_not_follow_links_out_of_the_tree() {
        let tmp = tempfile::tempdir().unwrap();
        let tmp = std::fs::canonicalize(tmp.path()).unwrap();
        let (root, outside) = (tmp.join("root"), tmp.join("outside"));
        std::fs::create_dir_all(&root).unwrap();
        std::fs::create_dir_all(&outside).unwrap();
        std::os::unix::fs::symlink(&outside, root.join("link")).unwrap();
        let calls = Arc::new(AtomicUsize::new(0));
        let c = calls.clone();
        let _w = watch(&root, move || {
            c.fetch_add(1, Ordering::SeqCst);
        })
        .unwrap();
        std::fs::write(outside.join("X.java"), "class X {}").unwrap();
        std::thread::sleep(Duration::from_millis(1200));
        assert_eq!(calls.load(Ordering::SeqCst), 0);
        std::fs::write(root.join("Y.java"), "class Y {}").unwrap();
        std::thread::sleep(Duration::from_millis(1200));
        assert_eq!(calls.load(Ordering::SeqCst), 1);
    }
}
