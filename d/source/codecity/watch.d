/// Watches the source tree and invokes a callback (debounced) whenever source files change.
/// Linux uses inotify; elsewhere the tree is polled once a second for modification-time changes.
///
/// The watcher runs on a daemon thread and calls `onChange` on that same thread, one call at a time: a rebuild
/// blocks further watching until it returns, and changes made meanwhile are picked up afterwards (inotify queues
/// the events; polling compares against the stamps taken before the rebuild). The callback is responsible for
/// handing the result to other threads safely (`CityServer.update` locks, the GUI does its own hand-off).
module codecity.watch;

import codecity.analysis : findSources, isIgnoredDir, languageOf;
import codecity.model : Language;
import core.thread : Thread;
import core.time : msecs, seconds;
import std.file : dirEntries, isDir, SpanMode, timeLastModified;
import std.path : baseName, buildPath;

/// How long the tree must stay quiet before a rebuild starts, so a burst of saves (or a `git checkout`) causes one
/// rebuild rather than many.
private enum debounce = 400.msecs;

/// Starts watching `root` in the background and returns immediately.
void watch(string root, void delegate() onChange)
{
    version (linux)
    {
        auto t = new Thread({ inotifyLoop(root, onChange); });
    }
    else
    {
        auto t = new Thread({ pollLoop(root, onChange); });
    }
    t.isDaemon = true;
    t.start();
}

/// inotify watches single directories, not trees, so every directory under `root` (except ignored and symlinked
/// ones, as in `findSources`) gets its own watch, and directories created later are added as they appear.
///
/// Debouncing: while nothing is pending, `poll` waits indefinitely. A relevant event sets `pending`, and from then
/// on `poll` waits at most `debounce`; every further event restarts that wait. When it times out with nothing new,
/// `onChange` runs.
version (linux) private void inotifyLoop(string root, void delegate() onChange)
{
    import core.sys.linux.sys.inotify;
    import core.sys.posix.poll : poll, pollfd, POLLIN;
    import core.sys.posix.unistd : read;
    import std.string : fromStringz, toStringz;

    const fd = inotify_init1(IN_CLOEXEC);
    if (fd < 0) return pollLoop(root, onChange);
    // Watch descriptor → watched directory, to rebuild the full path from an event's name. Entries are never
    // removed; a deleted directory's watch is simply never reported again.
    string[int] dirs;
    enum mask = IN_CREATE | IN_DELETE | IN_MODIFY | IN_MOVED_FROM | IN_MOVED_TO | IN_CLOSE_WRITE;
    void add(string dir)
    {
        const wd = inotify_add_watch(fd, dir.toStringz, mask);
        if (wd >= 0) dirs[wd] = dir;
        try
        {
            foreach (e; dirEntries(dir, SpanMode.shallow, false))
                if (!e.isSymlink && e.isDir && !isIgnoredDir(e.name.baseName)) add(e.name);
        }
        catch (Exception) {}
    }
    add(root);
    // One read returns as many whole events as fit; 64 KiB holds hundreds of them.
    ubyte[64 * 1024] buf;
    bool pending;
    while (true)
    {
        pollfd p = pollfd(fd, POLLIN, 0);
        const ready = poll(&p, 1, pending ? cast(int) debounce.total!"msecs" : -1);
        if (ready == 0 && pending)
        {
            pending = false; // quiet for a moment: rebuild
            onChange();
            continue;
        }
        if (ready <= 0) continue;
        const n = read(fd, buf.ptr, buf.length);
        if (n <= 0) continue;
        // The buffer holds variable-length records: a fixed inotify_event header followed by `len` bytes of
        // NUL-padded file name (len is 0 for events about the watched directory itself, which are skipped).
        size_t off = 0;
        while (off < n)
        {
            auto ev = cast(inotify_event*)(buf.ptr + off);
            const name = ev.len ? (cast(char*) ev.name.ptr).fromStringz.idup : "";
            off += inotify_event.sizeof + ev.len;
            // The kernel's queue overflowed and events were lost, so assume a change.
            if (ev.mask & IN_Q_OVERFLOW) pending = true;
            auto dir = ev.wd in dirs;
            if (dir is null || name.length == 0) continue;
            const path = buildPath(*dir, name);
            if (isIgnoredDir(name)) continue;
            // A new directory (created or moved in) must be watched too; files already in it are not reported
            // separately, so the change counts as relevant.
            if ((ev.mask & IN_ISDIR) && (ev.mask & (IN_CREATE | IN_MOVED_TO)))
            {
                add(path);
                pending = true;
            }
            // Any change to a source file counts, and to a Cargo.toml or package.json (they decide crate and module
            // names); so does the delete or move-out of a directory, because the name of
            // a removed directory says nothing about whether it held sources. Changes to other files do not.
            Language l;
            if (languageOf(name, l) || name == "Cargo.toml" || name == "package.json"
                    || ((ev.mask & IN_ISDIR) && (ev.mask & (IN_DELETE | IN_MOVED_FROM)))) pending = true;
        }
    }
}

/// Portable fallback: every second, takes a snapshot of the modification time of every source file and every
/// manifest (Cargo.toml, package.json) and compares it with the previous one. Added and deleted files change the
/// snapshot's keys, so they are noticed too. After a change it waits `debounce`, takes a fresh snapshot as the new
/// baseline and calls `onChange`.
private void pollLoop(string root, void delegate() onChange)
{
    long[string] stamps;
    long[string] scan()
    {
        long[string] s;
        // A file deleted between listing and stat'ing is just left out.
        string[] manifests;
        auto files = findSources(root, manifests);
        foreach (f; files ~ manifests)
            try s[f] = f.timeLastModified.stdTime;
            catch (Exception) {}
        return s;
    }
    stamps = scan();
    while (true)
    {
        Thread.sleep(1.seconds);
        auto now = scan();
        if (now != stamps)
        {
            Thread.sleep(debounce);
            stamps = scan();
            onChange();
        }
    }
}
