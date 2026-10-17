/// Minimal streaming JSON writer whose output is safe to embed inside an HTML `<script>` element
/// (mirrors the Java/Rust editions' number formatting so all three produce identical files).
module codecity.json;

import std.array : Appender, appender;
import std.format : format, formattedWrite;
import std.math : abs, round, trunc;

struct JsonWriter
{
    private Appender!string output;
    private bool needComma;

    static JsonWriter create()
    {
        JsonWriter w;
        w.output = appender!string;
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
    ref JsonWriter number(double v) return
    {
        comma();
        const r = round(v * 1000) / 1000;
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

/// Shortest decimal representation that round-trips (like Java's Double.toString / Rust's `{}` for our ranges).
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
