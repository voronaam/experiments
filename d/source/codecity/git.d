/// Mines `git log` once for per-file churn, author count and recency.
module codecity.git;

import codecity.model : FileHistory;
import std.conv : to;
import std.path : buildNormalizedPath;
import std.process : execute, pipeProcess, Redirect, wait;
import std.string : strip;

/// Output of `git -C dir args…`, or null if git fails or is unavailable.
string run(string dir, string[] args...)
{
    try
    {
        auto r = execute(["git", "-C", dir] ~ args);
        return r.status == 0 ? r.output.strip : null;
    }
    catch (Exception)
        return null;
}

/// Empty when `root` is not inside a git work tree or git is unavailable.
FileHistory[string] load(string root)
{
    FileHistory[string] result;
    auto top = run(root, "rev-parse", "--show-toplevel");
    if (top.length == 0) return result;
    top = realPath(top);
    struct Acc
    {
        uint commits;
        bool[string] authors;
        long last = long.min, first = long.max;
    }
    Acc[string] files;
    try
    {
        auto p = pipeProcess(["git", "-C", root, "-c", "core.quotepath=off", "log", "--no-merges", "--no-renames",
                "--format=%x1e%at%x1f%aE", "--name-only", "--", "*.java", "*.ts", "*.tsx", "*.mts", "*.cts", "*.rs"],
                Redirect.stdout | Redirect.stderr);
        long time;
        string author;
        foreach (line; p.stdout.byLineCopy)
        {
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
        wait(p.pid);
    }
    catch (Exception)
        return result;
    foreach (rel, a; files)
        result[buildNormalizedPath(top, rel)] = FileHistory(a.commits, cast(uint) a.authors.length, a.last, a.first);
    return result;
}

/// Canonical absolute path (symlinks resolved), like Java's toRealPath / Rust's canonicalize.
string realPath(string path)
{
    import core.sys.posix.stdlib : realpath;
    import core.stdc.stdlib : free;
    import std.string : fromStringz, toStringz;
    auto p = realpath(path.toStringz, null);
    if (p is null) return path;
    scope (exit) free(p);
    return p.fromStringz.idup;
}
