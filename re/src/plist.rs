//! Apple property-list parsing (Phase 7.2 — IPA analysis).
//!
//! Info.plist ships binary (`bplist00`) in App Store / Xcode builds and XML
//! in some hand-built archives; both formats land here as one [`PlistValue`]
//! tree. The binary parser walks the offset table and object encodings per
//! the CFBinaryPList spec (int/real/date/data/ascii/utf16/array/set/dict/uid,
//! 0xF length escape → next object carries the count as a signed int); the
//! XML side is a minimal recursive walk over the six plist primitives — no
//! full XML stack, just enough for a well-formed plist body.

use std::collections::BTreeMap;

use anyhow::{anyhow, bail, Result};

/// A parsed property list node.
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub enum PlistValue {
    /// `<dict>` — keys in document order (BTreeMap keeps parity with the
    /// binary format's sorted layout and gives deterministic rendering).
    Dict(BTreeMap<String, PlistValue>),
    Array(Vec<PlistValue>),
    Str(String),
    Int(i64),
    Real(f64),
    Bool(bool),
    Data(Vec<u8>),
    /// ISO-8601-ish date string as stored (binary plists carry a float
    /// epoch; rendered as seconds since 2001-01-01 converted to a string).
    Date(String),
    Uid(u64),
}

impl PlistValue {
    /// String content, arrays unwrapped to the first string (handy for
    /// `CFBundleSupportedPlatforms`).
    pub fn as_str(&self) -> Option<&str> {
        match self {
            PlistValue::Str(s) => Some(s),
            PlistValue::Array(a) => a.first().and_then(|v| v.as_str()),
            _ => None,
        }
    }

    /// Integer content (`Int` or `Bool` as 0/1 — device families).
    pub fn as_int(&self) -> Option<i64> {
        match self {
            PlistValue::Int(i) => Some(*i),
            PlistValue::Bool(b) => Some(*b as i64),
            _ => None,
        }
    }

    pub fn as_dict(&self) -> Option<&BTreeMap<String, PlistValue>> {
        match self {
            PlistValue::Dict(d) => Some(d),
            _ => None,
        }
    }

    pub fn as_array(&self) -> Option<&[PlistValue]> {
        match self {
            PlistValue::Array(a) => Some(a),
            _ => None,
        }
    }
}

/// Sniff and parse: `bplist00` → binary, `<` + plist doctype → XML.
pub fn parse_plist(bytes: &[u8]) -> Result<PlistValue> {
    if bytes.len() >= 8 && &bytes[..6] == b"bplist" {
        parse_binary_plist(bytes)
    } else if bytes.starts_with(b"<?xml") || bytes.windows(6).any(|w| w == b"<plist") {
        parse_xml_plist(bytes)
    } else {
        bail!("not a property list (neither bplist00 magic nor XML plist)")
    }
}

// ------------------------------------------------------------- binary ----

struct BinReader<'a> {
    data: &'a [u8],
    #[allow(dead_code)] // read from the trailer directly; kept for API parity
    offset_size: usize,
    ref_size: usize,
    offsets: Vec<u64>,
}

/// Binary plist (`bplist0?`).
pub fn parse_binary_plist(bytes: &[u8]) -> Result<PlistValue> {
    if bytes.len() < 8 + 32 {
        bail!("binary plist truncated ({min} bytes minimum)", min = 8 + 32);
    }
    if &bytes[..8] != b"bplist00" && &bytes[..8] != b"bplist01" {
        bail!("unsupported binary plist version (want bplist00/01, got {:?})", String::from_utf8_lossy(&bytes[..8]));
    }
    let trailer = &bytes[bytes.len() - 32..];
    // Trailer layout: 5 unused + sortVersion + offsetIntSize + objectRefSize,
    // then 8-byte numObjects / topObject / offsetTableOffset (big-endian).
    let offset_size = trailer[6] as usize;
    let ref_size = trailer[7] as usize;
    let num_objects = be_u64(&trailer[8..16]);
    let top_object = be_u64(&trailer[16..24]);
    let table_off = be_u64(&trailer[24..32]) as usize;
    if offset_size == 0 || offset_size > 8 || ref_size == 0 || ref_size > 8 {
        bail!("binary plist trailer has invalid offset/ref sizes ({offset_size}/{ref_size})");
    }
    if num_objects == 0 {
        bail!("binary plist has no objects");
    }
    // The offset table ends right where the 32-byte trailer begins.
    let Some(table_end) = table_off.checked_add(num_objects as usize * offset_size) else {
        bail!("binary plist offset table overflow");
    };
    if table_end + 32 != bytes.len() {
        bail!("binary plist offset table does not end at the trailer");
    }
    let mut offsets = Vec::with_capacity(num_objects.min(1 << 20) as usize);
    for i in 0..num_objects as usize {
        let at = table_off + i * offset_size;
        if at + offset_size > bytes.len() {
            bail!("binary plist offset table out of range");
        }
        offsets.push(be_uint(&bytes[at..at + offset_size]));
    }
    if top_object as usize >= offsets.len() {
        bail!("binary plist top object out of range");
    }
    let mut r = BinReader { data: bytes, offset_size, ref_size, offsets };
    let (v, _consumed) = r.read_object(top_object as usize)?;
    Ok(v)
}

fn be_u64(b: &[u8]) -> u64 {
    let mut v = 0u64;
    for x in b {
        v = (v << 8) | *x as u64;
    }
    v
}

fn be_uint(b: &[u8]) -> u64 {
    be_u64(b)
}

impl<'a> BinReader<'a> {
    fn ref_at(&self, at: usize, count: usize) -> Result<Vec<usize>> {
        let mut out = Vec::with_capacity(count);
        for i in 0..count {
            let p = at + i * self.ref_size;
            if p + self.ref_size > self.data.len() {
                bail!("binary plist object reference out of range");
            }
            out.push(be_uint(&self.data[p..p + self.ref_size]) as usize);
        }
        Ok(out)
    }

    fn read_object(&mut self, idx: usize) -> Result<(PlistValue, usize)> {
        let off = *self
            .offsets
            .get(idx)
            .ok_or_else(|| anyhow!("object {idx} has no offset"))? as usize;
        if off >= self.data.len() {
            bail!("object {idx} offset {off} out of range");
        }
        self.read_at(off)
    }

    fn read_at(&mut self, off: usize) -> Result<(PlistValue, usize)> {
        let marker = self.data[off];
        let (kind, nib) = (marker >> 4, marker & 0x0f);
        // int / real / data / string / array lengths use the 0xF escape:
        // the next object is an int carrying the true size.
        let size_of = |r: &BinReader, base: usize| -> Result<(usize, usize)> {
            // Returns (length, value_start) — value_start is where the
            // variable-sized body begins (after the escaped int if any).
            if nib != 0xf {
                return Ok((nib as usize, base));
            }
            let m = r.data.get(base).copied().ok_or_else(|| anyhow!("size object out of range"))?;
            if m >> 4 != 0x1 {
                bail!("0xF length escape must reference an int object");
            }
            let nbytes = 1usize << (m & 0xf).min(6); // sizes 2^n, capped sanity
            let p = base + 1;
            if p + nbytes > r.data.len() {
                bail!("length int out of range");
            }
            let n = be_uint(&r.data[p..p + nbytes]) as usize;
            Ok((n, p + nbytes))
        };
        let int_obj_at = |r: &BinReader, base: usize| -> Result<u64> {
            let nbytes = 1usize << (nib & 0xf).min(6);
            if base + nbytes > r.data.len() {
                bail!("int body out of range");
            }
            Ok(be_uint(&r.data[base..base + nbytes]))
        };

        match kind {
            0x0 => match nib {
                0x0 => Ok((PlistValue::Uid(0), 1)),
                0x8 => Ok((PlistValue::Bool(false), 1)),
                0x9 => Ok((PlistValue::Bool(true), 1)),
                0xf => Ok((PlistValue::Str(String::new()), 1)),
                other => bail!("unsupported simple marker {marker:#04x} (nib {other:#x})"),
            },
            0x1 => {
                let nbytes = 1usize << (nib & 0xf).min(6);
                let v = int_obj_at(self, off + 1)? as i64;
                // Apple encodes negative ints as two's complement at 8 bytes.
                let v = if nbytes == 8 { v as i64 } else { v };
                Ok((PlistValue::Int(v), 1 + nbytes))
            }
            0x2 => {
                let nbytes = 1usize << nib.clamp(2, 3); // 4-byte float, 8-byte double
                if off + 1 + nbytes > self.data.len() {
                    bail!("real body out of range");
                }
                let v = if nbytes == 8 {
                    f64::from_be_bytes(self.data[off + 1..off + 9].try_into().unwrap())
                } else {
                    f32::from_be_bytes(self.data[off + 1..off + 5].try_into().unwrap()) as f64
                };
                Ok((PlistValue::Real(v), 1 + nbytes))
            }
            0x3 => {
                // Core Data epoch (2001-01-01) float seconds → ISO string.
                if off + 9 > self.data.len() {
                    bail!("date body out of range");
                }
                let secs = f64::from_be_bytes(self.data[off + 1..off + 9].try_into().unwrap());
                Ok((PlistValue::Date(core_date_to_iso(secs)), 9))
            }
            0x4 => {
                let (n, start) = size_of(self, off + 1)?;
                let end = start.checked_add(n).ok_or_else(|| anyhow!("data length overflow"))?;
                if end > self.data.len() {
                    bail!("data out of range");
                }
                Ok((PlistValue::Data(self.data[start..end].to_vec()), end - off))
            }
            0x5 | 0x6 => {
                // ASCII string: n bytes; UTF-16: n UTF-16 code units.
                let (n, start) = size_of(self, off + 1)?;
                let v = if kind == 0x5 {
                    let end = start.checked_add(n).ok_or_else(|| anyhow!("string length overflow"))?;
                    if end > self.data.len() {
                        bail!("string out of range");
                    }
                    String::from_utf8_lossy(&self.data[start..end]).into_owned()
                } else {
                    let bytes = n.saturating_mul(2);
                    let end = start.checked_add(bytes).ok_or_else(|| anyhow!("string length overflow"))?;
                    if end > self.data.len() {
                        bail!("utf16 string out of range");
                    }
                    decode_utf16be(&self.data[start..end])
                };
                let width = if kind == 0x5 { 1 } else { 2 };
                Ok((PlistValue::Str(v), start - off + n.saturating_mul(width)))
            }
            0x8 => {
                // UID: low nibble encodes byte count (stored sizes vary).
                let nbytes = 1usize << nib.min(7);
                let p = off + 1;
                if p + nbytes > self.data.len() {
                    bail!("uid body out of range");
                }
                Ok((PlistValue::Uid(be_uint(&self.data[p..p + nbytes])), 1 + nbytes))
            }
            0xa | 0xc => {
                // array (0xAn); set (0xCn) is an unordered array — same shape.
                let (n, start) = size_of(self, off + 1)?;
                let refs = self.ref_at(start, n)?;
                let mut items = Vec::with_capacity(n.min(4096));
                for ridx in refs {
                    let (v, _) = self.read_object(ridx)?;
                    items.push(v);
                }
                Ok((PlistValue::Array(items), start + n.saturating_mul(self.ref_size) - off))
            }
            0xd => {
                // dict (0xDn): count key refs followed by count value refs.
                let (n, start) = size_of(self, off + 1)?;
                let krefs = self.ref_at(start, n)?;
                let vrefs = self.ref_at(start + n.saturating_mul(self.ref_size), n)?;
                let mut dict = BTreeMap::new();
                for (k, v) in krefs.into_iter().zip(vrefs) {
                    let (kv, _) = self.read_object(k)?;
                    let (vv, _) = self.read_object(v)?;
                    let key = match kv {
                        PlistValue::Str(s) => s,
                        _ => bail!("dict key is not a string"),
                    };
                    dict.insert(key, vv);
                }
                Ok((PlistValue::Dict(dict), start + 2 * n.saturating_mul(self.ref_size) - off))
            }
            other => bail!("unsupported plist object kind {other:#x} at {off}"),
        }
    }
}

/// Decode UTF-16BE into a Rust string (lone surrogates → U+FFFD).
fn decode_utf16be(b: &[u8]) -> String {
    let units: Vec<u16> = b
        .chunks_exact(2)
        .map(|c| u16::from_be_bytes([c[0], c[1]]))
        .collect();
    String::from_utf16_lossy(&units)
}

/// Core Data epoch (2001-01-01 00:00:00 UTC) → `YYYY-MM-DDTHH:MM:SSZ`.
fn core_date_to_iso(secs: f64) -> String {
    // 978307200 = unix epoch seconds at 2001-01-01.
    let unix = 978307200i64 + secs as i64;
    let days = unix.div_euclid(86_400);
    let tod = unix.rem_euclid(86_400);
    let (y, m, d) = civil_from_days(days);
    format!(
        "{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z",
        tod / 3600,
        (tod % 3600) / 60,
        tod % 60
    )
}

/// Days-since-epoch → civil date (Howard Hinnant's algorithm).
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

// ---------------------------------------------------------------- XML ----

/// Minimal XML plist parser: `<plist><dict|array|string|integer|real|true|false|data|date>` walk.
pub fn parse_xml_plist(bytes: &[u8]) -> Result<PlistValue> {
    let text = String::from_utf8_lossy(bytes);
    let body = match text.find("<plist") {
        Some(i) => {
            let after = &text[i..];
            let gt = after.find('>').ok_or_else(|| anyhow!("XML plist: <plist> tag not closed"))?;
            &after[gt + 1..]
        }
        None => text.as_ref(),
    };
    let (v, _used) = parse_xml_node(body.trim_start(), 0)?;
    Ok(v)
}

/// Byte-cursor over the plist body — no string re-borrowing gymnastics.
/// `cursor` is always an offset into `s` exactly as passed (leading
/// whitespace included), so callers can `cursor += used` directly.
fn parse_xml_node(s: &str, depth: u32) -> Result<(PlistValue, usize)> {
    if depth > 64 {
        bail!("XML plist nesting too deep");
    }
    let t = s.trim_start();
    if t.is_empty() {
        bail!("XML plist: unexpected end of body");
    }
    if !t.starts_with('<') {
        bail!("XML plist: expected a tag at {:?}", short(t));
    }
    let (name, after_open, self_closing) = open_tag(t)?;
    let mut cursor = s.len() - t.len() + (t.len() - after_open.len());

    // Self-closing primitives (`<true/>`, `<false/>`): nothing after the tag.
    if self_closing {
        let v = match name {
            "true" => PlistValue::Bool(true),
            "false" => PlistValue::Bool(false),
            "string" | "date" | "data" => PlistValue::Str(String::new()),
            other => bail!("XML plist: unsupported self-closing tag <{other}/>"),
        };
        return Ok((v, cursor));
    }

    // Leaf primitives: text up to `</name>` then the closing tag.
    if matches!(name, "true" | "false" | "string" | "integer" | "real" | "data" | "date" | "key") {
        let close = format!("</{name}>");
        let end = after_open
            .find(&close)
            .ok_or_else(|| anyhow!("XML plist: <{name}> not closed"))?;
        let text = &after_open[..end];
        cursor += end + close.len();
        let v = match name {
            "true" => PlistValue::Bool(true),
            "false" => PlistValue::Bool(false),
            "string" | "key" => PlistValue::Str(decode_entities(text)),
            "integer" => PlistValue::Int(
                text.trim()
                    .parse()
                    .map_err(|_| anyhow!("XML plist: bad integer {text:?}"))?,
            ),
            "real" => PlistValue::Real(
                text.trim()
                    .parse()
                    .map_err(|_| anyhow!("XML plist: bad real {text:?}"))?,
            ),
            "data" => PlistValue::Data(base64_loose(text)),
            _ => PlistValue::Date(text.trim().to_string()),
        };
        return Ok((v, cursor));
    }

    match name {
        "dict" => {
            let mut dict = BTreeMap::new();
            loop {
                let t2 = s[cursor..].trim_start();
                if t2.is_empty() {
                    bail!("XML plist: <dict> not closed");
                }
                cursor += s[cursor..].len() - t2.len();
                if t2.starts_with("</dict>") {
                    cursor += "</dict>".len();
                    return Ok((PlistValue::Dict(dict), cursor));
                }
                if !t2.starts_with("<key>") {
                    bail!("XML plist dict: expected <key>, found {:?}", short(t2));
                }
                // `<key>text</key>` parses as a leaf — take the whole node.
                let (kv, kused) = parse_xml_node(&s[cursor..], depth + 1)?;
                let key = match kv {
                    PlistValue::Str(k) => k,
                    _ => bail!("XML plist: <key> must contain text"),
                };
                cursor += kused;
                let (val, vused) = parse_xml_node(&s[cursor..], depth + 1)?;
                cursor += vused;
                dict.insert(key, val);
            }
        }
        "array" => {
            let mut items = Vec::new();
            loop {
                let t2 = s[cursor..].trim_start();
                if t2.is_empty() {
                    bail!("XML plist: <array> not closed");
                }
                cursor += s[cursor..].len() - t2.len();
                if t2.starts_with("</array>") {
                    cursor += "</array>".len();
                    return Ok((PlistValue::Array(items), cursor));
                }
                let (val, vused) = parse_xml_node(&s[cursor..], depth + 1)?;
                cursor += vused;
                items.push(val);
            }
        }
        other => bail!("XML plist: unsupported tag <{other}>"),
    }
}

/// `<tag ...>` → (tag name, rest after `>`, self-closing flag).
fn open_tag(s: &str) -> Result<(&str, &str, bool)> {
    let end = s.find('>').ok_or_else(|| anyhow!("XML plist: unterminated tag"))?;
    let raw = &s[1..end];
    let self_closing = raw.ends_with('/');
    let raw = if self_closing { &raw[..raw.len() - 1] } else { raw };
    let name = raw.split_whitespace().next().unwrap_or(raw);
    Ok((name, &s[end + 1..], self_closing))
}

fn short(s: &str) -> String {
    s.chars().take(24).collect()
}

/// Decode the five XML predefined entities plus numeric refs.
fn decode_entities(s: &str) -> String {
    if !s.contains('&') {
        return s.to_string();
    }
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(i) = rest.find('&') {
        out.push_str(&rest[..i]);
        let tail = &rest[i..];
        if let Some(semi) = tail.find(';') {
            let ent = &tail[1..semi];
            let decoded = match ent {
                "amp" => Some('&'),
                "lt" => Some('<'),
                "gt" => Some('>'),
                "quot" => Some('"'),
                "apos" => Some('\''),
                _ => {
                    if let Some(hex) = ent.strip_prefix("#x").or_else(|| ent.strip_prefix("#X")) {
                        u32::from_str_radix(hex, 16).ok().and_then(char::from_u32)
                    } else if let Some(dec) = ent.strip_prefix('#') {
                        dec.parse::<u32>().ok().and_then(char::from_u32)
                    } else {
                        None
                    }
                }
            };
            if let Some(c) = decoded {
                out.push(c);
                rest = &tail[semi + 1..];
            } else {
                out.push('&');
                rest = &tail[1..];
            }
        } else {
            out.push('&');
            rest = &tail[1..];
        }
    }
    out.push_str(rest);
    out
}

/// Whitespace-tolerant base64 (XML `<data>` wraps lines).
fn base64_loose(s: &str) -> Vec<u8> {
    const TBL: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut acc = 0u32;
    let mut bits = 0u32;
    let mut out = Vec::new();
    for c in s.bytes() {
        if c.is_ascii_whitespace() || c == b'=' {
            continue;
        }
        let v = match TBL.iter().position(|t| *t == c) {
            Some(v) => v as u32,
            None => continue,
        };
        acc = (acc << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bplist(bytes: &[u8]) -> PlistValue {
        parse_binary_plist(bytes).expect("parses")
    }

    #[test]
    fn binary_plist_roundtrip_dict() {
        let v = bplist(&test_fixture_binary_plist());
        let d = v.as_dict().unwrap();
        assert_eq!(d.get("id").unwrap().as_str(), Some("com.xen"));
        assert_eq!(d.get("ver").unwrap().as_str(), Some("1.0"));
        assert_eq!(d.get("flag").unwrap(), &PlistValue::Bool(true));
        let fam = d.get("fam").unwrap().as_array().unwrap();
        assert_eq!(fam.len(), 2);
        assert_eq!(fam[0].as_int(), Some(1));
        assert_eq!(fam[1].as_int(), Some(2));
    }

    #[test]
    fn binary_plist_escaped_length_string() {
        // 20-char string needs the 0xF escape: 0x5F + int(0x11, 20) + bytes.
        let s = "abcdefghijklmnopqrst";
        let mut obj = vec![0x5f, 0x11];
        obj.extend_from_slice(&(s.len() as u16).to_be_bytes());
        obj.extend_from_slice(s.as_bytes());
        let bytes = single_object_plist(&obj);
        let v = bplist(&bytes);
        assert_eq!(v, PlistValue::Str(s.to_string()));
    }

    #[test]
    fn binary_plist_utf16_string() {
        // "héllo" in UTF-16BE, marker 0x65 (5 code units).
        let mut obj = vec![0x65];
        for unit in "héllo".encode_utf16() {
            obj.extend_from_slice(&unit.to_be_bytes());
        }
        let bytes = single_object_plist(&obj);
        let v = bplist(&bytes);
        assert_eq!(v, PlistValue::Str("héllo".to_string()));
    }

    #[test]
    fn binary_plist_8byte_int_and_negative() {
        let bytes = single_object_plist(&{
            let mut v = vec![0x13];
            v.extend_from_slice(&(-7i64).to_be_bytes());
            v
        });
        assert_eq!(bplist(&bytes), PlistValue::Int(-7));
    }

    #[test]
    fn rejects_garbage() {
        assert!(parse_binary_plist(b"nonsense here!!nonsense").is_err());
        assert!(parse_plist(b"still not a plist").is_err());
        assert!(parse_binary_plist(b"bplist00").is_err());
    }

    /// One-object binary plist (top object = object 1, object 0 is null).
    fn single_object_plist(obj: &[u8]) -> Vec<u8> {
        let mut body = b"bplist00".to_vec();
        body.push(0x00); // object 0
        let off1 = body.len() as u64;
        body.extend_from_slice(obj);
        let table_off = body.len() as u64;
        body.extend_from_slice(&0u64.to_be_bytes()); // object 0 offset
        body.extend_from_slice(&off1.to_be_bytes());
        let mut trailer = vec![0u8; 6];
        trailer.push(8); // offset_int_size
        trailer.push(1); // object_ref_size
        trailer.extend_from_slice(&2u64.to_be_bytes()); // num objects
        trailer.extend_from_slice(&1u64.to_be_bytes()); // top object
        trailer.extend_from_slice(&table_off.to_be_bytes());
        body.extend_from_slice(&trailer);
        body
    }

    /// Hand-assembled bplist00: dict {"id":"com.xen", "ver":"1.0",
    /// "flag":true, "fam":[1,2]} — mirrors what Xcode writes for a minimal
    /// Info.plist (all strings short enough for 1-byte markers).
    fn test_fixture_binary_plist() -> Vec<u8> {
        fn s(text: &str) -> Vec<u8> {
            assert!(text.len() < 16, "fixture strings must be short");
            let mut v = vec![0x50 | text.len() as u8];
            v.extend_from_slice(text.as_bytes());
            v
        }
        let objects: Vec<Vec<u8>> = vec![
            vec![0x00],                                  // 0 null
            {
                let mut v = vec![0xd4];                  // 1 dict of 4
                v.extend_from_slice(&[0x02, 0x04, 0x06, 0x08]); // keys
                v.extend_from_slice(&[0x03, 0x05, 0x07, 0x09]); // values
                v
            },
            s("id"),                                     // 2
            s("com.xen"),                                // 3
            s("ver"),                                    // 4
            s("1.0"),                                    // 5
            s("flag"),                                   // 6
            vec![0x09],                                  // 7 true
            s("fam"),                                    // 8
            vec![0xa2, 0x0a, 0x0b],                      // 9 array [10, 11]
            vec![0x10, 0x01],                            // 10 int 1
            vec![0x10, 0x02],                            // 11 int 2
        ];
        let ref_size = 1usize;
        let mut body = b"bplist00".to_vec();
        let mut offsets = Vec::new();
        let mut pos = body.len() as u64;
        for o in &objects {
            offsets.push(pos);
            pos += o.len() as u64;
            body.extend_from_slice(o);
        }
        let offset_size = 2usize;
        let table_off = body.len() as u64;
        for off in &offsets {
            body.extend_from_slice(&(*off as u16).to_be_bytes());
        }
        let mut trailer = vec![0u8; 6];
        trailer.push(offset_size as u8);
        trailer.push(ref_size as u8);
        trailer.extend_from_slice(&(objects.len() as u64).to_be_bytes());
        trailer.extend_from_slice(&1u64.to_be_bytes()); // top object
        trailer.extend_from_slice(&table_off.to_be_bytes());
        body.extend_from_slice(&trailer);
        body
    }

    #[test]
    fn xml_plist_roundtrip() {
        let xml = br#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleIdentifier</key><string>com.xen.demo</string>
    <key>CFBundleShortVersionString</key><string>1.2.3</string>
    <key>MinimumOSVersion</key><string>14.0</string>
    <key>UISupportedDeviceFamilies</key><array><integer>1</integer><integer>2</integer></array>
    <key>Weird</key><string>a&amp;b &lt;c&gt;</string>
    <key>Flag</key><true/>
</dict>
</plist>"#;
        let v = parse_xml_plist(xml).expect("parses");
        let d = v.as_dict().unwrap();
        assert_eq!(d.get("CFBundleIdentifier").unwrap().as_str(), Some("com.xen.demo"));
        assert_eq!(d.get("MinimumOSVersion").unwrap().as_str(), Some("14.0"));
        assert_eq!(d.get("Weird").unwrap().as_str(), Some("a&b <c>"));
        assert_eq!(d.get("Flag").unwrap(), &PlistValue::Bool(true));
        assert_eq!(d.get("UISupportedDeviceFamilies").unwrap().as_array().unwrap().len(), 2);
    }
}
