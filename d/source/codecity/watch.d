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

/// A running watcher. The program never stops its watcher (it ends with the process); tests call `stop`, so no
/// watcher thread or inotify descriptor outlives its test.
final class Watcher
{
    private shared bool stopped;
    private Thread thread;

    /// Ends the watcher's thread (within about 200 ms, or after a rebuild in progress) and waits for it. Safe to call
    /// more than once.
    void stop()
    {
        import core.atomic : atomicStore;
        atomicStore(stopped, true);
        thread.join(false);
    }

    private bool stopping()
    {
        import core.atomic : atomicLoad;
        return atomicLoad(stopped);
    }
}

/// Starts watching `root` in the background and returns immediately; the result can stop it again.
Watcher watch(string root, void delegate() onChange)
{
    auto w = new Watcher;
    version (linux)
        w.thread = new Thread({ inotifyLoop(root, onChange, w); });
    else
        w.thread = new Thread({ pollLoop(root, onChange, w); });
    w.thread.isDaemon = true;
    w.thread.start();
    return w;
}

/// inotify watches single directories, not trees, so every directory under `root` (except ignored and symlinked
/// ones, as in `findSources`) gets its own watch, and directories created later are added as they appear.
///
/// Debouncing: while nothing is pending, `poll` waits indefinitely. A relevant event sets `pending`, and from then
/// on `poll` waits at most `debounce`; every further event restarts that wait. When it times out with nothing new,
/// `onChange` runs.
version (linux) private void inotifyLoop(string root, void delegate() onChange, Watcher w)
{
    import core.sys.linux.sys.inotify;
    import core.sys.posix.poll : poll, pollfd, POLLIN;
    import core.sys.posix.unistd : read;
    import std.string : fromStringz, toStringz;

    const fd = inotify_init1(IN_CLOEXEC);
    if (fd < 0) return pollLoop(root, onChange, w);
    scope (exit)
    {
        import core.sys.posix.unistd : close;
        close(fd);
    }
    // Watch descriptor → watched directory, to rebuild the full path from an event's name. Entries are never
    // removed; a deleted directory's watch is simply never reported again.
    string[int] dirs;
    enum mask = IN_CREATE | IN_DELETE | IN_MODIFY | IN_MOVED_FROM | IN_MOVED_TO | IN_CLOSE_WRITE;
    // Below the root, IN_DONT_FOLLOW and IN_ONLYDIR make the kernel refuse a path that has become a symbolic link (to
    // a directory anywhere) or a file since it was seen as a directory, so the walk's no-links rule holds even then.
    // The root itself may be reached through a link the user gave.
    void add(string dir)
    {
        const wd = inotify_add_watch(fd, dir.toStringz, dir == root ? mask : mask | IN_DONT_FOLLOW | IN_ONLYDIR);
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
    // While idle, wake every 200 ms anyway to notice `Watcher.stop`.
    while (!w.stopping)
    {
        pollfd p = pollfd(fd, POLLIN, 0);
        const ready = poll(&p, 1, pending ? cast(int) debounce.total!"msecs" : 200);
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
private void pollLoop(string root, void delegate() onChange, Watcher w)
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
    while (!w.stopping)
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

/// A change behind a symbolic link to a directory outside the tree is not watched (the walker does not follow links
/// either), while a change inside the tree is. The Rust edition has the same test.
version (linux) unittest
{
    import core.atomic : atomicLoad, atomicOp;
    import std.file : mkdirRecurse, rmdirRecurse, symlink, tempDir, write;
    import std.uuid : randomUUID;

    const tmp = buildPath(tempDir, "codecity-watch-test-" ~ randomUUID.toString);
    scope (exit) rmdirRecurse(tmp);
    const root = buildPath(tmp, "root"), outside = buildPath(tmp, "outside");
    mkdirRecurse(root);
    mkdirRecurse(outside);
    symlink(outside, buildPath(root, "link"));
    shared int calls;
    auto watcher = watch(root, { atomicOp!"+="(calls, 1); });
    scope (exit) watcher.stop();
    Thread.sleep(200.msecs); // the watcher registers the tree on its own thread
    write(buildPath(outside, "X.java"), "class X {}");
    Thread.sleep(1200.msecs);
    assert(atomicLoad(calls) == 0);
    write(buildPath(root, "Y.java"), "class Y {}");
    Thread.sleep(1200.msecs);
    assert(atomicLoad(calls) == 1);
}

/// Creating, modifying (in a burst) and deleting a source file, a new directory with a source file (and a later file
/// inside it, so the new directory is watched), the two manifests and deleting a directory each cause exactly one
/// rebuild. Other files, ignored directories (existing and new) and files changed in them cause none. The Java
/// (`SourceWatcherTest`) and Rust (`watch.rs`) editions have the same test. (The watcher is stopped at the end, so
/// neither its thread nor its inotify descriptor outlives the test.)
version (linux) unittest
{
    import core.atomic : atomicLoad, atomicOp;
    import core.time : MonoTime;
    import std.file : mkdirRecurse, remove, rmdirRecurse, tempDir, write;
    import std.format : format;
    import std.uuid : randomUUID;

    const root = buildPath(tempDir, "codecity-watch-test-" ~ randomUUID.toString);
    mkdirRecurse(buildPath(root, "target"));
    scope (exit) rmdirRecurse(root);
    void w(string rel, string text) { write(buildPath(root, rel), text); }
    w("package.json", "{}");
    shared int calls;
    // Waits until `n` rebuilds have happened, then leaves the tree alone for longer than the debounce and checks that
    // no further one followed.
    void expectCalls(int n)
    {
        const end = MonoTime.currTime + 30.seconds;
        while (atomicLoad(calls) < n && MonoTime.currTime < end) Thread.sleep(20.msecs);
        Thread.sleep(900.msecs);
        assert(atomicLoad(calls) == n, format("%d rebuilds, expected %d", atomicLoad(calls), n));
    }
    auto watcher = watch(root, { atomicOp!"+="(calls, 1); });
    scope (exit) watcher.stop();
    Thread.sleep(200.msecs); // the watcher registers the tree on its own thread

    w("notes.txt", "x");
    w("notes.txt", "y");
    w("target/X.java", "class X {}");
    mkdirRecurse(buildPath(root, "node_modules", "dep"));
    w("node_modules/dep/Y.java", "class Y {}");
    mkdirRecurse(buildPath(root, ".hidden"));
    w(".hidden/Z.java", "class Z {}");
    remove(buildPath(root, "notes.txt"));
    expectCalls(0);

    w("Main.java", "class Main {}");
    expectCalls(1);
    foreach (i; 0 .. 5) w("Main.java", format("class Main { int f%d; }", i));
    expectCalls(2);
    mkdirRecurse(buildPath(root, "sub"));
    w("sub/Sub.java", "class Sub {}");
    expectCalls(3);
    w("sub/Later.ts", "export class Later {}");
    expectCalls(4);
    w("sub/Cargo.toml", "[package]\nname = \"x\"\n");
    expectCalls(5);
    w("package.json", `{"name": "y"}`);
    expectCalls(6);
    remove(buildPath(root, "Main.java"));
    expectCalls(7);
    rmdirRecurse(buildPath(root, "sub"));
    expectCalls(8);
}
