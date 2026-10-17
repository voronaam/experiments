//! Minimal streaming JSON writer whose output is safe to embed inside an HTML `<script>` element
//! (mirrors the Java version's number formatting so both produce identical files).

use std::fmt::Write;

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
    pub fn num(&mut self, v: f64) -> &mut Self {
        self.comma();
        let r = (v * 1000.0).round() / 1000.0;
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
}
