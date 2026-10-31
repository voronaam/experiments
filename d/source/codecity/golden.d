/// End-to-end regression suite, shared by all three editions: runs the whole pipeline (`CityModel.build`, as
/// `--export` does) on every fixture under `testdata/` and compares the city JSON with its golden file in
/// `testdata/golden/`. The Java (`GoldenCityTest`) and Rust (`golden_tests.rs`) editions compare with the same files,
/// so the three are held to identical output.
///
/// The cases: each of `java-project`, `ts-project`, `rust-project` and `mixed-project` without git history (the
/// fixtures live inside CodeCity's own repository, whose history keeps changing), with and without test code
/// (`<name>.no-tests.json`); and the repository built by `testdata/make-history-repo.sh` (fixed authors and dates, so
/// fixed commit ids) with git history (`history-repo.json`) and with the history replay (`history-repo.history.json`).
/// That repository is built under a fixed directory name, as the city is named after it.
///
/// Both documents are normalised first, by the rules of `scripts/city_json.py` (which the other editions implement
/// too): `generatedAt` and `buildMillis` are removed, `root` becomes `"<root>"` and every building's `deps` are sorted.
/// A mismatch fails with the first differences, and the actual JSON is kept in `d/.dub/golden-actual/`. To regenerate
/// the golden files after an intended change, build all three editions and run `scripts/parity.sh --update-golden`,
/// which refuses to write them unless the editions agree.
module codecity.golden;

version (unittest):

import codecity.analysis : SourceAnalyzer;
import codecity.city : CityModel;
import codecity.git : realPath;
import codecity.github : GitHubInfo;
import codecity.history : defaultSamples;
import std.algorithm : all, canFind, filter, map, sort, uniq;
import std.array : array, join;
import std.file : mkdirRecurse, readText, rmdirRecurse, tempDir, write;
import std.format : format;
import std.json;
import std.path : buildNormalizedPath, buildPath, dirName;
import std.process : execute;
import std.uuid : randomUUID;

/// At most this many differences are reported, as in `city_json.py`.
private enum maxDiffs = 10;

private immutable fixtures = ["java-project", "ts-project", "rust-project", "mixed-project"];

/// The repository's `testdata` directory, found from this source file (d/source/codecity/golden.d).
private string testdata()
{
    return buildNormalizedPath(__FILE_FULL_PATH__.dirName, "..", "..", "..", "testdata");
}

/// Where the actual JSON of each case is kept for inspection.
private string actualDir()
{
    return buildNormalizedPath(__FILE_FULL_PATH__.dirName, "..", "..", ".dub", "golden-actual");
}

/// Builds the city of `tree` like the command line does (the real path, no GitHub) and compares its normalised JSON
/// with `testdata/golden/<golden>.json`; returns the differences as an error message, or null.
private string check(string golden, string tree, bool includeTests, bool useGit, uint history)
{
    auto model = CityModel.build(new SourceAnalyzer, realPath(tree), includeTests, useGit, false, GitHubInfo.init,
        history);
    const json = model.json();
    const actual = buildPath(actualDir, golden ~ ".json");
    mkdirRecurse(actualDir);
    write(actual, json);
    auto expected = parseJSON(readText(buildPath(testdata, "golden", golden ~ ".json")));
    string[] diffs;
    diff(normalise(expected), normalise(parseJSON(json)), "$", diffs);
    if (diffs.length == 0) return null;
    return format!"%s.json: the city differs from the golden file (actual JSON in %s):\n    %s"(golden, actual,
        diffs.join("\n    "));
}

/// Fails with every error of `errors` (null entries are passes).
private void assertAll(string[] errors)
{
    auto failed = errors.filter!(e => e !is null).array;
    assert(failed.length == 0, failed.join("\n")
        ~ "\nIf the change is intended, regenerate the golden files with scripts/parity.sh --update-golden.");
}

/// Builds the history fixture in `dir/history-repo`.
private string historyRepo(string dir)
{
    const repo = buildPath(dir, "history-repo");
    const r = execute(["sh", buildPath(testdata, "make-history-repo.sh"), repo]);
    assert(r.status == 0, "make-history-repo.sh failed:\n" ~ r.output);
    return repo;
}

/// The normalisation of `scripts/city_json.py`, in place; returns `city`.
private JSONValue normalise(JSONValue city)
{
    if (city.type != JSONType.object) return city;
    city.object.remove("generatedAt");
    city.object.remove("buildMillis");
    if (auto stats = "stats" in city.object)
        if (stats.type == JSONType.object) stats.object.remove("buildMillis");
    if ("root" in city.object) city.object["root"] = JSONValue("<root>");
    if (auto buildings = "buildings" in city.object)
        if (buildings.type == JSONType.array)
            foreach (ref b; buildings.array)
                if (b.type == JSONType.object)
                    if (auto deps = "deps" in b.object)
                        if (deps.type == JSONType.array) deps.array.sort!((x, y) => number(x) < number(y));
    return city;
}

private bool isNumber(const JSONValue v)
{
    return v.type == JSONType.integer || v.type == JSONType.uinteger || v.type == JSONType.float_;
}

/// A number's value as a double, whichever way std.json stored it.
private double number(const JSONValue v)
{
    switch (v.type)
    {
    case JSONType.integer: return v.integer;
    case JSONType.uinteger: return v.uinteger;
    case JSONType.float_: return v.floating;
    default: return double.nan;
    }
}

/// The ids (as JSON text) of an array of objects that all have one (districts, buildings), else null.
private string[] ids(const JSONValue[] list)
{
    if (list.length == 0 || !list.all!(x => x.type == JSONType.object && ("id" in x.object) !is null)) return null;
    return list.map!(x => x.object["id"].toString).array;
}

/// A value as compact JSON, cut to 80 characters, for a difference report.
private string label(const JSONValue v)
{
    return cut(v.toString);
}

private string cut(string s)
{
    import std.utf : count, toUTF8;
    import std.range : take;
    return s.count <= 80 ? s : s.take(77).toUTF8 ~ "...";
}

/// Appends up to `maxDiffs` differences between `a` (expected) and `b` (actual) to `out_`, depth first, like
/// `city_json.py`: object keys in sorted order, numbers by value, and an array of objects with ids (districts,
/// buildings) whose ids differ reported by its missing and unexpected ids.
private void diff(const JSONValue a, const JSONValue b, string path, ref string[] out_)
{
    if (out_.length >= maxDiffs) return;
    if (isNumber(a) && isNumber(b))
    {
        if (number(a) != number(b)) out_ ~= format!"%s: expected %s, got %s"(path, label(a), label(b));
    }
    else if (a.type == JSONType.object && b.type == JSONType.object)
    {
        auto keys = (a.object.keys ~ b.object.keys).sort.uniq.array;
        foreach (k; keys)
        {
            auto x = k in a.object, y = k in b.object;
            if (y is null) out_ ~= format!"%s.%s: missing (expected %s)"(path, k, label(*x));
            else if (x is null) out_ ~= format!"%s.%s: unexpected (got %s)"(path, k, label(*y));
            else diff(*x, *y, path ~ "." ~ k, out_);
            if (out_.length >= maxDiffs) return;
        }
    }
    else if (a.type == JSONType.array && b.type == JSONType.array)
    {
        const la = a.array, lb = b.array;
        auto ia = ids(la), ib = ids(lb);
        if (ia !is null && ib !is null && ia != ib)
        {
            auto missing = ia.filter!(x => !ib.canFind(x)).array;
            auto extra = ib.filter!(x => !ia.canFind(x)).array;
            out_ ~= missing.length == 0 && extra.length == 0 ? path ~ ": same ids in a different order"
                : format!"%s: ids differ; missing %s, unexpected %s"(path, cut("[" ~ missing.join(", ") ~ "]"),
                    cut("[" ~ extra.join(", ") ~ "]"));
            return;
        }
        if (la.length != lb.length)
            out_ ~= format!"%s: expected %s elements, got %s"(path, la.length, lb.length);
        foreach (i; 0 .. la.length < lb.length ? la.length : lb.length)
        {
            const x = la[i];
            string id;
            if (x.type == JSONType.object)
                if (auto p = "id" in x.object)
                    if (p.type == JSONType.string) id = "{" ~ p.str ~ "}";
            diff(x, lb[i], format!"%s[%s]%s"(path, i, id), out_);
            if (out_.length >= maxDiffs) return;
        }
    }
    else if (a != b)
    {
        out_ ~= format!"%s: expected %s, got %s"(path, label(a), label(b));
    }
}

unittest // golden: the four fixtures, without git history
{
    string[] errors;
    foreach (name; fixtures) errors ~= check(name, buildPath(testdata, name), true, false, 0);
    assertAll(errors);
}

unittest // golden: the four fixtures without test code (--no-tests)
{
    string[] errors;
    foreach (name; fixtures) errors ~= check(name ~ ".no-tests", buildPath(testdata, name), false, false, 0);
    assertAll(errors);
}

unittest // golden: the history repository with git history, then with the history replay (--history)
{
    const dir = buildPath(tempDir, "codecity-golden-" ~ randomUUID.toString);
    mkdirRecurse(dir);
    scope (exit) rmdirRecurse(dir);
    const repo = historyRepo(dir);
    assertAll([
        check("history-repo", repo, true, true, 0),
        check("history-repo.history", repo, true, true, defaultSamples),
    ]);
}
