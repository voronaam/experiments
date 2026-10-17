/// Watches the source tree and invokes a callback (debounced) whenever source files change.
/// Linux uses inotify; elsewhere the tree is polled once a second for modification-time changes.
module codecity.watch;

import codecity.analysis : findSources, isIgnoredDir, languageOf;
import codecity.model : Language;
import core.thread : Thread;
import core.time : msecs, seconds;
import std.file : dirEntries, isDir, SpanMode, timeLastModified;
import std.path : baseName, buildPath;

private enum debounce = 400.msecs;

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

version (linux) private void inotifyLoop(string root, void delegate() onChange)
{
    import core.sys.linux.sys.inotify;
    import core.sys.posix.poll : poll, pollfd, POLLIN;
    import core.sys.posix.unistd : read;
    import std.string : fromStringz, toStringz;

    const fd = inotify_init1(IN_CLOEXEC);
    if (fd < 0) return pollLoop(root, onChange);
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
        size_t off = 0;
        while (off < n)
        {
            auto ev = cast(inotify_event*)(buf.ptr + off);
            const name = ev.len ? (cast(char*) ev.name.ptr).fromStringz.idup : "";
            off += inotify_event.sizeof + ev.len;
            auto dir = ev.wd in dirs;
            if (dir is null || name.length == 0) continue;
            const path = buildPath(*dir, name);
            if (isIgnoredDir(name)) continue;
            if ((ev.mask & IN_ISDIR) && (ev.mask & (IN_CREATE | IN_MOVED_TO)))
            {
                add(path);
                pending = true;
            }
            Language l;
            if (languageOf(name, l) || (ev.mask & (IN_DELETE | IN_MOVED_FROM))) pending = true;
        }
    }
}

private void pollLoop(string root, void delegate() onChange)
{
    long[string] stamps;
    long[string] scan()
    {
        long[string] s;
        foreach (f; findSources(root))
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
