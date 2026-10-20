//! Minimal streaming JSON writer whose output is safe to embed inside an HTML `<script>` element
//! (mirrors the Java version's number formatting so both produce identical files).
//!
//! The writer is a builder: every method appends to one `String` and returns `&mut Self`, so a document is written as
//! a chain such as `w.begin_object().field_str("a", "x").name("b").begin_array().int(1).end_array().end_object()`.
//! It does not check structure; callers must balance `begin_*`/`end_*` and put a `name` before every value inside an
//! object.
//!
//! Commas are handled by one flag, `need_comma`: it is true right after a complete value (so the next value or name
//! needs a `,` first) and false right after `{`, `[` or `name:` (where the next value must follow directly).

use std::fmt::Write;

/// Builds one JSON document in memory; take it with [`JsonWriter::finish`].
pub struct JsonWriter {
    out: String,
    need_comma: bool,
}

impl Default for JsonWriter {
    fn default() -> Self {
        Self::new()
    }
}

impl JsonWriter {
    pub fn new() -> Self {
        // Pre-sized to 64 KiB to save the first few regrowths while a city document is written.
        JsonWriter { out: String::with_capacity(1 << 16), need_comma: false }
    }

    fn comma(&mut self) {
        if self.need_comma {
            self.out.push(',');
        }
    }

    pub fn begin_object(&mut self) -> &mut Self {
        self.comma();
        self.out.push('{');
        self.need_comma = false;
        self
    }

    pub fn end_object(&mut self) -> &mut Self {
        self.out.push('}');
        self.need_comma = true;
        self
    }

    pub fn begin_array(&mut self) -> &mut Self {
        self.comma();
        self.out.push('[');
        self.need_comma = false;
        self
    }

    pub fn end_array(&mut self) -> &mut Self {
        self.out.push(']');
        self.need_comma = true;
        self
    }

    /// Writes an object key and its colon; the value written next belongs to it.
    pub fn name(&mut self, name: &str) -> &mut Self {
        self.comma();
        self.string(name);
        self.out.push(':');
        self.need_comma = false;
        self
    }

    pub fn str(&mut self, s: &str) -> &mut Self {
        self.comma();
        self.string(s);
        self.need_comma = true;
        self
    }

    pub fn opt_str(&mut self, s: Option<&str>) -> &mut Self {
        match s {
            Some(s) => self.str(s),
            None => self.raw("null"),
        }
    }

    pub fn int(&mut self, v: i64) -> &mut Self {
        self.comma();
        let _ = write!(self.out, "{v}");
        self.need_comma = true;
        self
    }

    /// Rounded to 3 decimals; whole numbers are written without a fraction.
    ///
    /// Three decimals keep the file small. Rust's `{}` for `f64` prints the shortest representation that
    /// round-trips (`0.4`, not `0.40000000000000002`) and never uses an exponent. The rounding is Java's
    /// `Math.round`, which rounds halves up (`-2.0625` → `-2.062`), not `f64::round`, which rounds them away from
    /// zero; the 1e15 bound is the Java edition's too. JSON has no NaN or infinity, so those are written as `0`.
    pub fn num(&mut self, v: f64) -> &mut Self {
        self.comma();
        let r = if v.is_finite() { round_half_up(v * 1000.0) / 1000.0 } else { 0.0 };
        // `2` rather than `2.0`. Larger whole values are printed by the float branch (as in the Java edition).
        if r == r.trunc() && r.abs() < 1e15 {
            let _ = write!(self.out, "{}", r as i64);
        } else {
            let _ = write!(self.out, "{r}");
        }
        self.need_comma = true;
        self
    }

    pub fn bool(&mut self, v: bool) -> &mut Self {
        self.raw(if v { "true" } else { "false" })
    }

    /// Writes `json` verbatim as a value; the caller must make sure it is valid JSON.
    pub fn raw(&mut self, json: &str) -> &mut Self {
        self.comma();
        self.out.push_str(json);
        self.need_comma = true;
        self
    }

    pub fn field_str(&mut self, n: &str, v: &str) -> &mut Self {
        self.name(n).str(v)
    }

    pub fn field_int(&mut self, n: &str, v: i64) -> &mut Self {
        self.name(n).int(v)
    }

    pub fn field_num(&mut self, n: &str, v: f64) -> &mut Self {
        self.name(n).num(v)
    }

    pub fn field_bool(&mut self, n: &str, v: bool) -> &mut Self {
        self.name(n).bool(v)
    }

    /// Writes a quoted, escaped string literal. On top of what JSON requires (quote, backslash, control characters),
    /// it escapes `<`, `>` and `&` as `\u003c` etc., so the output can never contain `</script>` or `<!--` and is safe
    /// inside an HTML `<script>` element (see `export.rs`). U+2028 and U+2029 are escaped too: older JavaScript
    /// engines treat them as line terminators, which would break the string if the JSON were evaluated as script.
    fn string(&mut self, s: &str) {
        self.out.push('"');
        for c in s.chars() {
            match c {
                '"' => self.out.push_str("\\\""),
                '\\' => self.out.push_str("\\\\"),
                '\n' => self.out.push_str("\\n"),
                '\r' => self.out.push_str("\\r"),
                '\t' => self.out.push_str("\\t"),
                c if (c as u32) < 0x20 || matches!(c, '<' | '>' | '&' | '\u{2028}' | '\u{2029}') => {
                    let _ = write!(self.out, "\\u{:04x}", c as u32);
                }
                c => self.out.push(c),
            }
        }
        self.out.push('"');
    }

    pub fn finish(self) -> String {
        self.out
    }
}

/// Nearest whole number, halves rounded up (towards positive infinity), like Java's `Math.round`. `x - floor(x)` is
/// exact for every finite double, so the comparison with 0.5 is too.
fn round_half_up(x: f64) -> f64 {
    let f = x.floor();
    if x - f >= 0.5 { f + 1.0 } else { f }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_html_safe_json_with_java_number_format() {
        let mut w = JsonWriter::new();
        w.begin_object().field_str("a", "</script>").field_num("b", 2.0).field_num("c", 0.4).field_num("d", 1.23456);
        w.name("e").begin_array().int(1).int(2).end_array().end_object();
        let escaped = format!("{0}u003c/script{0}u003e", '\\');
        assert_eq!(w.finish(), format!(r#"{{"a":"{escaped}","b":2,"c":0.4,"d":1.235,"e":[1,2]}}"#));
    }

    /// A hostile string (from a file name, commit message or pull request title) cannot end or confuse the
    /// `<script>` element an export embeds the JSON in, nor break a JavaScript string. The Java and D editions have
    /// the same test.
    #[test]
    fn escapes_everything_that_could_escape_a_script_element() {
        let mut w = JsonWriter::new();
        w.begin_array().str("</script><!--\u{2028}\u{2029}\u{1}&>\"\\").end_array();
        assert_eq!(w.finish(), r#"["\u003c/script\u003e\u003c!--\u2028\u2029\u0001\u0026\u003e\"\\"]"#);
    }

    /// The viewer files an export inlines as they are must not contain what would end their element early.
    #[test]
    fn inlined_viewer_files_cannot_end_their_elements() {
        let (js, css) = (crate::assets::APP_JS.to_ascii_lowercase(), crate::assets::STYLE_CSS.to_ascii_lowercase());
        assert!(!js.contains("</script") && !js.contains("<!--"));
        assert!(!css.contains("</style"));
    }

    #[test]
    fn rounds_halves_up_like_java_and_writes_non_finite_as_zero() {
        let mut w = JsonWriter::new();
        w.begin_array();
        // 2.0625 * 1000 = 2062.5 exactly, so these are true ties at the third decimal.
        for v in [2.0625, -2.0625, -1.0, f64::NAN, f64::INFINITY, f64::NEG_INFINITY] {
            w.num(v);
        }
        w.end_array();
        assert_eq!(w.finish(), "[2.063,-2.062,-1,0,0,0]");
    }
}
