/// Minimal streaming JSON writer whose output is safe to embed inside an HTML `<script>` element
/// (mirrors the Java/Rust editions' number formatting so all three produce identical files).
///
/// Used for the `/api/city` and `/api/source` responses and for the data embedded by `--export`. Calls are chained;
/// the writer does not check nesting, the caller is responsible for matching every begin with an end.
module codecity.json;

import std.array : Appender, appender;
import std.format : format, formattedWrite;
import std.math : abs, floor, isFinite, trunc;

/// Appends JSON text to an in-memory buffer; `create` returns one with a pre-sized buffer.
struct JsonWriter
{
    private Appender!string output;
    /// True after a complete value, so the next value or name needs a leading comma. Reset after `{`, `[` and a
    /// name's `:`, where the next value must not get one.
    private bool needComma;

    static JsonWriter create()
    {
        JsonWriter w;
        w.output = appender!string;
        // 64 KiB up front: the city JSON is usually larger, so this avoids the first few reallocations.
        w.output.reserve(1 << 16);
        return w;
    }

    private void comma()
    {
        if (needComma) output.put(',');
    }

    ref JsonWriter beginObject() return { comma(); output.put('{'); needComma = false; return this; }
    ref JsonWriter endObject() return { output.put('}'); needComma = true; return this; }
    ref JsonWriter beginArray() return { comma(); output.put('['); needComma = false; return this; }
    ref JsonWriter endArray() return { output.put(']'); needComma = true; return this; }

    ref JsonWriter name(string n) return
    {
        comma();
        str(n);
        output.put(':');
        needComma = false;
        return this;
    }

    /// A string; an empty D string (which may be `null`) is written as "".
    ref JsonWriter value(string s) return
    {
        comma();
        str(s);
        needComma = true;
        return this;
    }

    /// A string that is JSON `null` when absent.
    ref JsonWriter nullable(string s) return
    {
        comma();
        if (s is null) output.put("null");
        else str(s);
        needComma = true;
        return this;
    }

    ref JsonWriter value(long v) return
    {
        comma();
        output.formattedWrite("%d", v);
        needComma = true;
        return this;
    }

    /// Rounded to 3 decimals; whole numbers are written without a fraction.
    ///
    /// Rounding hides the floating-point noise of the layout's sums (e.g. 12.000000000001) and keeps the files small.
    /// The rounding is Java's `Math.round`, which rounds halves up (`-2.0625` → `-2.062`), not `std.math.round`,
    /// which rounds them away from zero. Whole values below 1e15 are printed as integers (`2`, not `2.0`); the
    /// limit keeps them well inside the range where a double holds every integer exactly and `cast(long)` is exact.
    /// JSON has no NaN or infinity, so those are written as `0`.
    ref JsonWriter number(double v) return
    {
        comma();
        const double r = isFinite(v) ? roundHalfUp(v * 1000) / 1000 : 0;
        if (r == trunc(r) && abs(r) < 1e15) output.formattedWrite("%d", cast(long) r);
        else output.put(shortest(r));
        needComma = true;
        return this;
    }

    ref JsonWriter boolean(bool v) return
    {
        comma();
        output.put(v ? "true" : "false");
        needComma = true;
        return this;
    }

    ref JsonWriter field(string n, string v) return { return name(n).value(v); }
    ref JsonWriter field(string n, long v) return { return name(n).value(v); }
    ref JsonWriter fieldNum(string n, double v) return { return name(n).number(v); }
    ref JsonWriter fieldBool(string n, bool v) return { return name(n).boolean(v); }

    /// Writes `s` as a quoted JSON string. Besides what JSON requires (quotes, backslashes, control characters),
    /// `<`, `>` and `&` are written as `\u` escapes so the output can sit inside an HTML `<script>` element
    /// (`</script>` can never appear), and U+2028/U+2029 because older JavaScript does not allow them unescaped in
    /// string literals. Iterating as `dchar` decodes UTF-8, so invalid UTF-8 in `s` throws.
    private void str(string s)
    {
        output.put('"');
        foreach (dchar c; s)
        {
            switch (c)
            {
            case '"': output.put(`\"`); break;
            case '\\': output.put(`\\`); break;
            case '\n': output.put(`\n`); break;
            case '\r': output.put(`\r`); break;
            case '\t': output.put(`\t`); break;
            default:
                if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == '\u2028' || c == '\u2029')
                    output.formattedWrite("\\u%04x", cast(uint) c);
                else
                    output.put(c);
            }
        }
        output.put('"');
    }

    string finish() { return output.data; }
}

/// Nearest whole number, halves rounded up (towards positive infinity), like Java's `Math.round`. `x - floor(x)` is
/// exact for every finite double, so the comparison with 0.5 is too.
private double roundHalfUp(double x)
{
    const double f = floor(x);
    return x - f >= 0.5 ? f + 1 : f;
}

/// Shortest decimal representation that round-trips (like Java's Double.toString / Rust's `{}` for our ranges).
///
/// Tries 1, 2, … significant digits and returns the first that parses back to exactly `v`. 17 significant digits
/// always round-trip a double, so the final `%.17g` is only a safety net.
string shortest(double v)
{
    foreach (precision; 1 .. 18)
    {
        auto s = format("%.*g", precision, v);
        import std.conv : to;
        if (s.to!double == v)
        {
            // %g may produce exponent notation for tiny/huge values; our values are rounded to 3 decimals
            if (s.length && s[0] == '.') s = "0" ~ s;
            return s;
        }
    }
    return format("%.17g", v);
}

unittest
{
    auto w = JsonWriter.create();
    w.beginObject().field("a", "</script>").fieldNum("b", 2.0).fieldNum("c", 0.4).fieldNum("d", 1.23456);
    w.name("e").beginArray().value(1).value(2).endArray().endObject();
    assert(w.finish() == `{"a":"\u003c/script\u003e","b":2,"c":0.4,"d":1.235,"e":[1,2]}`);
    assert(shortest(12.345) == "12.345" && shortest(-0.5) == "-0.5");
}

unittest
{
    auto w = JsonWriter.create();
    w.beginArray();
    // 2.0625 * 1000 = 2062.5 exactly, so these are true ties at the third decimal.
    foreach (v; [2.0625, -2.0625, -1.0, double.nan, double.infinity, -double.infinity]) w.number(v);
    w.endArray();
    assert(w.finish() == "[2.063,-2.062,-1,0,0,0]");
}
