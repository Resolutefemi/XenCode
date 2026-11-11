//! Android binary XML (AXML) parsing (Phase 7.2 — APK manifests).
//!
//! `AndroidManifest.xml` ships in APKs as a compiled chunk stream: a string
//! pool, optionally a resource map, then start/end namespace + start/end tag
//! chunks with typed attribute values. This parser walks the chunk tree and
//! rebuilds an element tree with attribute names resolved against the pool —
//! enough to read package/version/SDK attrs, permissions, components and
//! intent filters without aapt.

use std::collections::BTreeMap;

use anyhow::{anyhow, bail, Result};

/// One attribute value — the subset of Res_value types the manifest uses.
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub enum AttrValue {
    Str(String),
    Int(i64),
    Bool(bool),
    /// Resource reference (`@android:...` / `@*android:...` id).
    Ref(u32),
    /// Anything else kept raw (float, dimension, fraction, color…).
    Raw(u32, u8),
}

/// One parsed element: name + attributes (by attribute name, e.g. `name`,
/// `package` — namespace prefix dropped, first occurrence wins) + children.
#[derive(Debug, Clone, serde::Serialize)]
pub struct AxmlNode {
    pub name: String,
    pub attrs: BTreeMap<String, AttrValue>,
    pub children: Vec<AxmlNode>,
}

impl AxmlNode {
    pub fn attr(&self, name: &str) -> Option<&AttrValue> {
        self.attrs.get(name)
    }

    pub fn attr_str(&self, name: &str) -> Option<String> {
        match self.attr(name) {
            Some(AttrValue::Str(s)) => Some(s.clone()),
            _ => None,
        }
    }

    pub fn attr_int(&self, name: &str) -> Option<i64> {
        match self.attr(name) {
            Some(AttrValue::Int(i)) => Some(*i),
            Some(AttrValue::Bool(b)) => Some(*b as i64),
            _ => None,
        }
    }

    pub fn attr_bool(&self, name: &str) -> Option<bool> {
        match self.attr(name) {
            Some(AttrValue::Bool(b)) => Some(*b),
            _ => None,
        }
    }

    /// First-level children with a given tag name.
    pub fn children_named(&self, name: &str) -> Vec<&AxmlNode> {
        self.children.iter().filter(|c| c.name == name).collect()
    }

    /// All descendants with a given tag name (depth-first).
    pub fn find_all<'a>(&'a self, name: &str, out: &mut Vec<&'a AxmlNode>) {
        for c in &self.children {
            if c.name == name {
                out.push(c);
            }
            c.find_all(name, out);
        }
    }
}

/// Parse a compiled binary XML document (AndroidManifest.xml, res/*).
pub fn parse_axml(bytes: &[u8]) -> Result<AxmlNode> {
    if bytes.len() < 8 {
        bail!("AXML too short ({})", bytes.len());
    }
    let kind = le_u16(bytes, 0);
    let size = le_u32(bytes, 4);
    if kind != 0x0003 {
        bail!("not a binary XML chunk (type {kind:#06x})");
    }
    if size as usize > bytes.len() {
        bail!("AXML chunk size {size} exceeds buffer {}", bytes.len());
    }
    let end = size as usize;

    // First sub-chunk must be the string pool.
    let (mut pos, strings) = read_string_pool(bytes, end)?;

    // Walk the rest: resource map / namespaces / text skipped, tags build the tree.
    let mut stack: Vec<(AxmlNode, u32)> = Vec::new();
    let mut root: Option<AxmlNode> = None;
    while pos + 8 <= end {
        let (kind, header, size) = chunk_header(bytes, pos)?;
        if size < header as u32 || pos + size as usize > end {
            bail!("AXML sub-chunk {kind:#06x} overruns the document");
        }
        let body_end = pos + size as usize;
        match kind {
            0x0002 | 0x0100 | 0x0101 | 0x0104 => {
                // resource map, namespace start/end, text — no tree impact
            }
            0x0102 => {
                // ResXMLTree_attrExt begins at +16: ns, name, attributeStart,
                // attributeSize, attributeCount, idIndex, classIndex, styleIndex.
                if header < 16 || body_end < pos + 36 {
                    bail!("AXML start tag truncated");
                }
                let name_idx = le_u32(bytes, pos + 20);
                let attr_start = le_u16(bytes, pos + 24) as usize;
                let attr_size = le_u16(bytes, pos + 26) as usize;
                let attr_count = le_u16(bytes, pos + 28) as usize;
                let node = AxmlNode {
                    name: pool_string(&strings, name_idx)?,
                    attrs: read_attrs(bytes, pos + 16 + attr_start, attr_size, attr_count, body_end, &strings)?,
                    children: Vec::new(),
                };
                stack.push((node, name_idx));
            }
            0x0103 => {
                // End tag: ns@16, name@20 (ResXMLTree_endElementExt at +16).
                if body_end < pos + 24 {
                    bail!("AXML end tag truncated");
                }
                let close_idx = le_u32(bytes, pos + 20);
                let Some((node, open_idx)) = stack.pop() else {
                    bail!("AXML end tag without a matching start tag");
                };
                if close_idx != 0xFFFF_FFFF && close_idx != open_idx {
                    bail!(
                        "AXML tag mismatch: <{}> closed by </{}>",
                        pool_string(&strings, open_idx)?,
                        pool_string(&strings, close_idx)?
                    );
                }
                match stack.last_mut() {
                    Some((parent, _)) => parent.children.push(node),
                    None => {
                        if root.is_some() {
                            bail!("AXML has multiple root elements");
                        }
                        root = Some(node);
                    }
                }
            }
            other => bail!("unsupported AXML chunk {other:#06x} at {pos}"),
        }
        pos += size as usize;
    }
    if !stack.is_empty() {
        bail!("AXML ended with {} unclosed element(s)", stack.len());
    }
    root.ok_or_else(|| anyhow!("AXML had no root element"))
}

fn chunk_header(bytes: &[u8], pos: usize) -> Result<(u16, u16, u32)> {
    if pos + 8 > bytes.len() {
        bail!("chunk header truncated at {pos}");
    }
    Ok((
        le_u16(bytes, pos),
        le_u16(bytes, pos + 2),
        le_u32(bytes, pos + 4),
    ))
}

/// Res_string_pool (type 0x0001) — UTF-8 (flag 0x100) or UTF-16 encoding.
fn read_string_pool(bytes: &[u8], doc_end: usize) -> Result<(usize, Vec<String>)> {
    let (kind, header, size) = chunk_header(bytes, 8)?;
    if kind != 0x0001 {
        bail!("AXML: expected string pool first, got chunk {kind:#06x}");
    }
    if header < 28 || 8 + size as usize > doc_end {
        bail!("AXML string pool truncated (header {header}, size {size})");
    }
    let p = 8;
    let count = le_u32(bytes, p + 8) as usize;
    let flags = le_u32(bytes, p + 16);
    let strings_start = le_u32(bytes, p + 20) as usize;
    let offsets_start = p + header as usize;
    let utf8 = flags & 0x100 != 0;
    let pool_base = p + strings_start;
    let mut strings = Vec::with_capacity(count.min(1 << 14));
    for i in 0..count {
        let off_pos = offsets_start + i * 4;
        if off_pos + 4 > bytes.len() {
            bail!("AXML string pool offset {i} out of range");
        }
        let at = pool_base
            .checked_add(le_u32(bytes, off_pos) as usize)
            .ok_or_else(|| anyhow!("AXML string {i} offset overflow"))?;
        if at >= bytes.len() {
            bail!("AXML string {i} offset out of range");
        }
        strings.push(if utf8 {
            // Two length prefixes (char count, byte count), each 1–2 bytes,
            // then the bytes, then a NUL.
            let (_char_len, after1) = utf8_len_prefix(bytes, at)?;
            let (byte_len, data_start) = utf8_len_prefix(bytes, after1)?;
            let stop = data_start
                .checked_add(byte_len)
                .ok_or_else(|| anyhow!("AXML utf8 string length overflow"))?;
            if stop >= bytes.len() {
                bail!("AXML utf8 string {i} out of range");
            }
            String::from_utf8_lossy(&bytes[data_start..stop]).into_owned()
        } else {
            // UTF-16: u16 length (high bit → one more u16), units, NUL.
            let mut len = le_u16(bytes, at) as usize;
            let mut after = at + 2;
            if len & 0x8000 != 0 {
                if after + 2 > bytes.len() {
                    bail!("AXML utf16 length truncated");
                }
                len = ((len & 0x7fff) << 16) | le_u16(bytes, after) as usize;
                after += 2;
            }
            let stop = after
                .checked_add(len.saturating_mul(2))
                .ok_or_else(|| anyhow!("AXML utf16 string length overflow"))?;
            if stop + 2 > bytes.len() {
                bail!("AXML utf16 string {i} out of range");
            }
            let units: Vec<u16> = (0..len).map(|k| le_u16(bytes, after + k * 2)).collect();
            String::from_utf16_lossy(&units)
        });
    }
    Ok((8 + size as usize, strings))
}

/// UTF-8 pool length prefix: 1 byte, high bit → 1 more byte (big-endian pair).
fn utf8_len_prefix(bytes: &[u8], at: usize) -> Result<(usize, usize)> {
    if at >= bytes.len() {
        bail!("utf8 length prefix out of range");
    }
    let b0 = bytes[at] as usize;
    if b0 & 0x80 != 0 {
        if at + 1 >= bytes.len() {
            bail!("utf8 length prefix truncated");
        }
        Ok((((b0 & 0x7f) << 8) | bytes[at + 1] as usize, at + 2))
    } else {
        Ok((b0, at + 1))
    }
}

fn pool_string(strings: &[String], idx: u32) -> Result<String> {
    if idx == 0xFFFF_FFFF {
        return Ok(String::new());
    }
    strings
        .get(idx as usize)
        .cloned()
        .ok_or_else(|| anyhow!("string pool index {idx} out of range ({} entries)", strings.len()))
}

/// Read `attributeCount` ResXMLTree_attribute records of `attrSize` bytes:
/// ns, name, rawValue, then Res_value {size, res0, dataType, data}.
fn read_attrs(
    bytes: &[u8],
    base: usize,
    attr_size: usize,
    count: usize,
    body_end: usize,
    strings: &[String],
) -> Result<BTreeMap<String, AttrValue>> {
    let mut attrs = BTreeMap::new();
    if attr_size < 20 || count > 4096 {
        bail!("AXML attribute record size {attr_size} invalid (or {count} attrs)");
    }
    for i in 0..count {
        let at = base + i * attr_size;
        if at + 20 > body_end {
            bail!("AXML attribute {i} out of range");
        }
        let name_idx = le_u32(bytes, at + 4);
        let data_type = bytes[at + 15];
        let data = le_u32(bytes, at + 16);
        let name = pool_string(strings, name_idx)?;
        if name.is_empty() {
            continue;
        }
        let value = match data_type {
            0x03 => AttrValue::Str(pool_string(strings, data)?),
            0x10 | 0x11 => AttrValue::Int(data as i32 as i64), // int / hex int
            0x12 => AttrValue::Bool(data != 0),                // 0=false, -1=true
            0x01 => AttrValue::Ref(data),
            _ => AttrValue::Raw(data, data_type),
        };
        attrs.entry(name).or_insert(value);
    }
    Ok(attrs)
}

// Little-endian readers (used by the parser and the test fixture builder).
fn le_u16(bytes: &[u8], at: usize) -> u16 {
    u16::from_le_bytes([bytes[at], bytes[at + 1]])
}

fn le_u32(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
}

#[cfg(test)]
pub(crate) mod fixture {
    use super::*;

    // ------------------------------------------------------------ builder --
    // Minimal AXML writer used to build manifest fixtures (mirrors what
    // aapt2 emits: string pool + start/end tags with typed attributes).

    pub struct Builder {
        strings: Vec<String>,
        utf8: bool,
        chunks: Vec<u8>,
    }

    impl Builder {
        pub fn new(utf8: bool) -> Self {
            Builder { strings: Vec::new(), utf8, chunks: Vec::new() }
        }

        fn s(&mut self, v: &str) -> u32 {
            if let Some(i) = self.strings.iter().position(|s| s == v) {
                return i as u32;
            }
            self.strings.push(v.to_string());
            (self.strings.len() - 1) as u32
        }

        /// Start tag with typed attributes.
        pub fn start(&mut self, name: &str, attrs: &[(&str, AttrValue)]) {
            let mut encoded = Vec::new();
            for (an, av) in attrs {
                let ni = self.s(an);
                let mut a = Vec::new();
                a.extend_from_slice(&0xFFFF_FFFFu32.to_le_bytes()); // ns (none)
                a.extend_from_slice(&ni.to_le_bytes());
                a.extend_from_slice(&0xFFFF_FFFFu32.to_le_bytes()); // rawValue
                a.extend_from_slice(&8u16.to_le_bytes()); // Res_value size
                a.push(0); // res0
                let (dt, dd): (u8, u32) = match av {
                    AttrValue::Str(s) => (0x03, self.s(s)),
                    AttrValue::Int(i) => (0x10, *i as i32 as u32),
                    AttrValue::Bool(b) => (0x12, if *b { 0xFFFF_FFFF } else { 0 }),
                    AttrValue::Ref(r) => (0x01, *r),
                    AttrValue::Raw(d, t) => (*t, *d),
                };
                a.push(dt);
                a.extend_from_slice(&dd.to_le_bytes());
                encoded.extend_from_slice(&a);
            }
            let attr_size = 20u16;
            let attr_start = 20u16;
            let count = attrs.len() as u16;
            let size = 36 + count as u32 * attr_size as u32;
            let mut c = Vec::new();
            c.extend_from_slice(&0x0102u16.to_le_bytes());
            c.extend_from_slice(&16u16.to_le_bytes()); // headerSize
            c.extend_from_slice(&size.to_le_bytes());
            c.extend_from_slice(&1u32.to_le_bytes()); // line
            c.extend_from_slice(&0xFFFF_FFFFu32.to_le_bytes()); // comment
            c.extend_from_slice(&0xFFFF_FFFFu32.to_le_bytes()); // ns
            c.extend_from_slice(&self.s(name).to_le_bytes());
            c.extend_from_slice(&attr_start.to_le_bytes());
            c.extend_from_slice(&attr_size.to_le_bytes());
            c.extend_from_slice(&count.to_le_bytes());
            c.extend_from_slice(&0u16.to_le_bytes()); // idIndex
            c.extend_from_slice(&0u16.to_le_bytes()); // classIndex
            c.extend_from_slice(&0u16.to_le_bytes()); // styleIndex
            c.extend_from_slice(&encoded);
            self.chunks.extend_from_slice(&c);
        }

        pub fn end(&mut self, name: &str) {
            let mut c = Vec::new();
            c.extend_from_slice(&0x0103u16.to_le_bytes());
            c.extend_from_slice(&16u16.to_le_bytes()); // headerSize
            c.extend_from_slice(&24u32.to_le_bytes()); // size
            c.extend_from_slice(&1u32.to_le_bytes()); // line
            c.extend_from_slice(&0xFFFF_FFFFu32.to_le_bytes()); // comment
            c.extend_from_slice(&0xFFFF_FFFFu32.to_le_bytes()); // ns
            c.extend_from_slice(&self.s(name).to_le_bytes());
            self.chunks.extend_from_slice(&c);
        }

        fn string_pool(&self) -> Vec<u8> {
            let count = self.strings.len();
            let mut offsets = Vec::with_capacity(count * 4);
            let mut data = Vec::new();
            for s in &self.strings {
                offsets.push(data.len() as u32);
                if self.utf8 {
                    // charLen, byteLen, bytes, NUL (fixtures stay ascii so
                    // both prefixes are single bytes).
                    data.push(s.len() as u8);
                    data.push(s.len() as u8);
                    data.extend_from_slice(s.as_bytes());
                    data.push(0);
                } else {
                    let units: Vec<u16> = s.encode_utf16().collect();
                    data.extend_from_slice(&(units.len() as u16).to_le_bytes());
                    for u in units {
                        data.extend_from_slice(&u.to_le_bytes());
                    }
                    data.extend_from_slice(&0u16.to_le_bytes());
                }
            }
            while data.len() % 4 != 0 {
                data.push(0);
            }
            let header = 28u16;
            let strings_start = header as u32 + (offsets.len() * 4) as u32;
            let size = strings_start + data.len() as u32;
            let mut c = Vec::new();
            c.extend_from_slice(&0x0001u16.to_le_bytes());
            c.extend_from_slice(&header.to_le_bytes());
            c.extend_from_slice(&size.to_le_bytes());
            c.extend_from_slice(&(count as u32).to_le_bytes());
            c.extend_from_slice(&0u32.to_le_bytes()); // styles
            let flags: u32 = if self.utf8 { 0x100 } else { 0 };
            c.extend_from_slice(&flags.to_le_bytes());
            c.extend_from_slice(&strings_start.to_le_bytes());
            c.extend_from_slice(&0u32.to_le_bytes()); // stylesStart
            for off in &offsets {
                c.extend_from_slice(&off.to_le_bytes());
            }
            c.extend_from_slice(&data);
            c
        }

        pub fn finish(mut self) -> Vec<u8> {
            let pool = self.string_pool();
            let mut out = Vec::new();
            let total = 8 + pool.len() as u32 + self.chunks.len() as u32;
            out.extend_from_slice(&0x0003u16.to_le_bytes());
            out.extend_from_slice(&8u16.to_le_bytes());
            out.extend_from_slice(&total.to_le_bytes());
            out.extend_from_slice(&pool);
            out.append(&mut self.chunks);
            out
        }
    }

    /// A realistic compiled AndroidManifest: package/version/SDK attrs,
    /// two permissions, application with a launcher activity + a service.
    pub fn manifest_axml(utf8: bool) -> Vec<u8> {
        let mut b = Builder::new(utf8);
        b.start(
            "manifest",
            &[
                ("package", AttrValue::Str("com.xen.demo".to_string())),
                ("versionCode", AttrValue::Int(7)),
                ("versionName", AttrValue::Str("0.7.0".to_string())),
                ("platformBuildVersionCode", AttrValue::Int(34)),
                ("compileSdkVersion", AttrValue::Int(34)),
            ],
        );
        b.start("uses-permission", &[("name", AttrValue::Str("android.permission.INTERNET".to_string()))]);
        b.end("uses-permission");
        b.start("uses-permission", &[("name", AttrValue::Str("android.permission.CAMERA".to_string()))]);
        b.end("uses-permission");
        b.start(
            "application",
            &[
                ("label", AttrValue::Str("XenDemo".to_string())),
                ("debuggable", AttrValue::Bool(false)),
                ("allowBackup", AttrValue::Bool(true)),
                ("icon", AttrValue::Ref(0x7f0e_0001)),
            ],
        );
        b.start(
            "activity",
            &[
                ("name", AttrValue::Str("com.xen.demo.App".to_string())),
                ("exported", AttrValue::Bool(true)),
            ],
        );
        b.start("intent-filter", &[]);
        b.start("action", &[("name", AttrValue::Str("android.intent.action.MAIN".to_string()))]);
        b.end("action");
        b.start("category", &[("name", AttrValue::Str("android.intent.category.LAUNCHER".to_string()))]);
        b.end("category");
        b.end("intent-filter");
        b.end("activity");
        b.start("service", &[("name", AttrValue::Str("com.xen.demo.SyncService".to_string()))]);
        b.end("service");
        b.start("receiver", &[("name", AttrValue::Str("com.xen.demo.BootReceiver".to_string()))]);
        b.end("receiver");
        b.end("application");
        b.end("manifest");
        b.finish()
    }
}

#[cfg(test)]
mod tests {
    use super::fixture::manifest_axml;
    use super::*;

    #[test]
    fn parses_manifest_utf16_pool() {
        let doc = parse_axml(&manifest_axml(false)).expect("parses");
        assert_eq!(doc.name, "manifest");
        assert_eq!(doc.attr_str("package"), Some("com.xen.demo".to_string()));
        assert_eq!(doc.attr_int("versionCode"), Some(7));
        assert_eq!(doc.attr_str("versionName"), Some("0.7.0".to_string()));
        assert_eq!(doc.attr_int("compileSdkVersion"), Some(34));

        let app = doc.children_named("application").into_iter().next().unwrap();
        assert_eq!(app.attr_str("label"), Some("XenDemo".to_string()));
        assert_eq!(app.attr_bool("debuggable"), Some(false));
        assert_eq!(app.attr_bool("allowBackup"), Some(true));
        assert!(matches!(app.attr("icon"), Some(AttrValue::Ref(0x7f0e_0001))));

        let acts = app.children_named("activity");
        assert_eq!(acts.len(), 1);
        assert_eq!(acts[0].attr_str("name"), Some("com.xen.demo.App".to_string()));
        assert_eq!(acts[0].attr_bool("exported"), Some(true));
        assert_eq!(acts[0].children_named("intent-filter").len(), 1);
        assert_eq!(app.children_named("service").len(), 1);
        assert_eq!(app.children_named("receiver").len(), 1);
    }

    #[test]
    fn parses_manifest_utf8_pool() {
        let doc = parse_axml(&manifest_axml(true)).expect("parses");
        assert_eq!(doc.attr_str("package"), Some("com.xen.demo".to_string()));
        let app = doc.children_named("application").into_iter().next().unwrap();
        assert_eq!(app.children_named("service").len(), 1);
    }

    #[test]
    fn depth_first_find_all() {
        let doc = parse_axml(&manifest_axml(false)).expect("parses");
        let mut found = Vec::new();
        doc.find_all("uses-permission", &mut found);
        assert_eq!(found.len(), 2);
        assert_eq!(found[0].attr_str("name"), Some("android.permission.INTERNET".to_string()));
        assert_eq!(found[1].attr_str("name"), Some("android.permission.CAMERA".to_string()));
        let mut actions = Vec::new();
        doc.find_all("action", &mut actions);
        assert_eq!(actions.len(), 1);
        assert_eq!(actions[0].attr_str("name"), Some("android.intent.action.MAIN".to_string()));
    }

    #[test]
    fn rejects_truncated_and_garbage() {
        assert!(parse_axml(&manifest_axml(false)[..20]).is_err());
        assert!(parse_axml(b"\x01\x00\x08\x00").is_err());
        assert!(parse_axml(&[]).is_err());
    }
}
