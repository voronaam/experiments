/// Mines `git log` once for per-file churn, author count and recency.
///
/// Runs on its own thread while the sources are parsed (see `CityModel.build`). The result is keyed by absolute
/// path so it can be matched against `TypeDecl.file`. Also hosts the small git helpers used by `codecity.github`.
module codecity.git;

import codecity.model : FileHistory;
import core.time : seconds;
import std.conv : to;
import std.path : buildNormalizedPath;
import std.process : kill, pipe, Pid, spawnProcess, tryWait, wait;
import std.stdio : File;
import std.string : strip;

/// How long a short command (`run`, `gh auth token`) may take before `output` kills it.
private enum timeout = 30.seconds;

/// Output of `git -C dir args…`, or null if git fails, is unavailable or times out (see `output`).
string run(string dir, string[] args...)
{
    return output(["git", "-C", dir] ~ args);
}

/// Output of a short command, with surrounding whitespace stripped and invalid UTF-8 replaced (`lossyUtf8`); null if
/// it cannot be started, exits with an error or takes longer than `timeout`. stderr is discarded, so it can neither
/// fill an unread pipe nor end up in the result. stdout is read with `poll` against the deadline, so the limit also
/// covers a command that hangs while keeping stdout open: it is then killed.
string output(string[] cmd)
{
    import core.stdc.errno : errno, EINTR;
    import core.sys.posix.poll : poll, pollfd, POLLIN;
    import core.sys.posix.unistd : read;
    import core.thread : Thread;
    import core.time : MonoTime, msecs;
    Pid pid;
    try
    {
        auto p = pipe();
        pid = spawn(cmd, p.writeEnd);
        const deadline = MonoTime.currTime + timeout;
        bool timedOut()
        {
            if (MonoTime.currTime < deadline) return false;
            kill(pid);
            wait(pid);
            return true;
        }
        ubyte[] data;
        ubyte[4096] buf;
        while (true)
        {
            if (timedOut()) return null;
            auto fd = pollfd(p.readEnd.fileno, POLLIN, 0);
            if (poll(&fd, 1, cast(int)(deadline - MonoTime.currTime).total!"msecs" + 1) <= 0) continue;
            const n = read(p.readEnd.fileno, buf.ptr, buf.length);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) break; // end of output (or a read error)
            data ~= buf[0 .. n];
        }
        // stdout is closed, so the command is about to exit; poll for that until the deadline.
        while (true)
        {
            auto status = tryWait(pid);
            if (status.terminated) return status.status == 0 ? lossyUtf8(cast(char[]) data).strip : null;
            if (timedOut()) return null;
            Thread.sleep(10.msecs);
        }
    }
    catch (Exception)
    {
        if (pid !is null)
            try { kill(pid); wait(pid); } catch (Exception) {}
        return null;
    }
}

/// Starts `cmd` with stdin and stderr on the null device and stdout on `stdout`. `spawnProcess` closes the
/// parent's copies of these files, so the read end of a pipe sees end-of-file once the child is done.
private Pid spawn(string[] cmd, File stdout)
{
    return spawnProcess(cmd, File("/dev/null"), stdout, File("/dev/null", "w"));
}

/// `s` with every ill-formed UTF-8 sequence replaced by U+FFFD, one per maximal subpart (the longest prefix of a
/// valid sequence, or else a single byte), as Unicode recommends. This is what Java's UTF-8 decoder and Rust's
/// `String::from_utf8_lossy` do, so non-UTF-8 git output (a file name or an e-mail) comes out the same in all
/// editions.
string lossyUtf8(const(char)[] s)
{
    import std.utf : validate;
    try
    {
        validate(s);
        return s.idup;
    }
    catch (Exception) {}
    string result;
    size_t i = 0;
    while (i < s.length)
    {
        const b = cast(ubyte) s[i];
        // Length of the sequence `b` starts, and the allowed range of the byte after it (Unicode table 3-7); the
        // narrower ranges exclude overlong forms, surrogates and code points above U+10FFFF.
        size_t len;
        ubyte lo = 0x80, hi = 0xBF;
        if (b < 0x80) len = 1;
        else if (b >= 0xC2 && b <= 0xDF) len = 2;
        else if (b >= 0xE0 && b <= 0xEF)
        {
            len = 3;
            if (b == 0xE0) lo = 0xA0;
            if (b == 0xED) hi = 0x9F;
        }
        else if (b >= 0xF0 && b <= 0xF4)
        {
            len = 4;
            if (b == 0xF0) lo = 0x90;
            if (b == 0xF4) hi = 0x8F;
        }
        size_t n = 1;
        while (len > 1 && n < len && i + n < s.length)
        {
            const c = cast(ubyte) s[i + n];
            if (c < (n == 1 ? lo : 0x80) || c > (n == 1 ? hi : 0xBF)) break;
            n++;
        }
        if (len > 0 && n == len) result ~= s[i .. i + n];
        else result ~= "\uFFFD";
        i += n;
    }
    return result;
}

/// Empty when `root` is not inside a git work tree or git is unavailable.
///
/// One `git log` over the whole history of the supported source files, streamed line by line. With the format
/// below, every commit starts with a header line `\x1e<author time>\x1f<author e-mail>` (ASCII record and unit
/// separators, which do not occur in a timestamp or a normal e-mail address), followed by a blank line and the names
/// of the files it touched, one per line. Blank lines are skipped; file lines belong to the most recent header.
///
/// Merges are skipped (`--no-merges`). With `--no-renames` a rename is listed as the old and the new path, and the
/// file's history under its new name starts at the rename. Any failure, including a malformed timestamp, yields an
/// empty result rather than an error.
FileHistory[string] load(string root)
{
    FileHistory[string] result;
    auto top = run(root, "rev-parse", "--show-toplevel");
    if (top.length == 0) return result;
    // Paths in the log are relative to the top level, and the analysed paths are canonical, so make the top level
    // canonical too.
    top = realPath(top);
    // Per-file accumulator while the log is read; authors are a set of e-mail addresses.
    struct Acc
    {
        uint commits;
        bool[string] authors;
        long last = long.min, first = long.max;
    }
    Acc[string] files;
    try
    {
        // core.quotepath=off: print non-ASCII file names verbatim instead of quoted octal escapes. The pathspecs are
        // the extensions the analysers handle (`codecity.analysis.languageOf`); they are relative to `root`, so a
        // root below the top level only sees its own files.
        // stderr goes to the null device: a pipe that is never read would block git once it is full.
        auto p = pipe();
        auto pid = spawn(["git", "-C", root, "-c", "core.quotepath=off", "log", "--no-merges", "--no-renames",
                "--format=%x1e%at%x1f%aE", "--name-only", "--", "*.java", "*.ts", "*.tsx", "*.mts", "*.cts", "*.rs"],
                p.writeEnd);
        long time;
        string author;
        // Each line is decoded lossily: a path or e-mail that is not valid UTF-8 gets U+FFFD in place of the bad
        // bytes, as in the other editions. A trailing "\r" is dropped like Java's readLine and Rust's lines() do.
        foreach (raw; p.readEnd.byLine)
        {
            string line = lossyUtf8(raw.length && raw[$ - 1] == '\r' ? raw[0 .. $ - 1] : raw);
            if (line.length && line[0] == '\x1e')
            {
                import std.string : indexOf;
                const sep = line.indexOf('\x1f');
                time = sep > 0 ? line[1 .. sep].to!long : 0;
                author = sep > 0 ? line[sep + 1 .. $] : "";
            }
            else if (line.strip.length)
            {
                auto acc = line in files;
                if (acc is null)
                {
                    files[line] = Acc();
                    acc = line in files;
                }
                acc.commits++;
                acc.authors[author] = true;
                if (time > acc.last) acc.last = time;
                if (time < acc.first) acc.first = time;
            }
        }
        wait(pid);
    }
    catch (Exception)
        return result;
    foreach (rel, a; files)
        result[buildNormalizedPath(top, rel)] = FileHistory(a.commits, cast(uint) a.authors.length, a.last, a.first);
    return result;
}

/// Canonical absolute path (symlinks resolved), like Java's toRealPath / Rust's canonicalize.
/// Returns `path` unchanged when it cannot be resolved (e.g. it does not exist).
string realPath(string path)
{
    import core.sys.posix.stdlib : realpath;
    import core.stdc.stdlib : free;
    import std.string : fromStringz, toStringz;
    // With a null buffer, POSIX realpath allocates the result with malloc; it is freed after copying.
    auto p = realpath(path.toStringz, null);
    if (p is null) return path;
    scope (exit) free(p);
    return p.fromStringz.idup;
}

unittest
{
    // Valid text is unchanged; each maximal subpart of an ill-formed sequence becomes one U+FFFD.
    assert(lossyUtf8("a\u00e9\U0001F600") == "a\u00e9\U0001F600");
    assert(lossyUtf8("B\xFF.java") == "B\uFFFD.java");
    assert(lossyUtf8("\xE2\x82a") == "\uFFFDa"); // truncated 3-byte sequence
    assert(lossyUtf8("\xC0\xAF") == "\uFFFD\uFFFD"); // overlong: both bytes invalid on their own
    assert(lossyUtf8("\xED\xA0\x80") == "\uFFFD\uFFFD\uFFFD"); // surrogate
    assert(lossyUtf8("\xF0\x9F\x98") == "\uFFFD"); // truncated 4-byte sequence
}

unittest
{
    // The newest commit adds a file whose name, and whose author's e-mail, are not valid UTF-8 (byte 0xFF). They are
    // read with U+FFFD in place of the bad byte, and the older history after them is still read.
    import std.file : mkdirRecurse, rmdirRecurse, tempDir;
    import std.path : buildPath;
    import std.process : Config, execute;
    import std.uuid : randomUUID;
    const dir = buildPath(tempDir, "codecity-git-" ~ randomUUID.toString);
    mkdirRecurse(dir);
    scope (exit) rmdirRecurse(dir);
    const script = `set -e
git init -q .
git config user.name t
git config user.email t@example.com
echo a > A.java
git add A.java
git -c commit.gpgsign=false commit -q -m a
echo b > "$(printf 'B\377.java')"
git add -A
git -c commit.gpgsign=false commit -q -m b --author="$(printf 'x <x\377@example.com>')"
`;
    assert(execute(["sh", "-c", script], null, Config.none, size_t.max, dir).status == 0);
    const top = realPath(dir);
    auto history = load(top);
    assert(history.length == 2);
    assert(history[buildNormalizedPath(top, "A.java")].commits == 1);
    assert(history[buildNormalizedPath(top, "B\uFFFD.java")].authors == 1);
}
