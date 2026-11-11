//! Android DEX parsing (Phase 7.2 — APK class inventory).
//!
//! Reads the header tables (strings, types, protos, fields, methods, class
//! defs) plus per-class `class_data` to produce a class/method inventory —
//! descriptors, access flags, super classes, native-method flags — without
//! executing or decompiling anything. Multiple dex files (classes.dex,
//! classes2.dex, …) are each parsed by the caller.

use anyhow::{bail, Result};

/// Little-endian u32 reader (DEX is fully little-endian).
fn le_u32(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
}

/// Largest DEX this parser will touch (a stray 4 GiB classes.dex stays honest).
pub const MAX_DEX_BYTES: usize = 256 * 1024 * 1024;
/// Class-list cap: counts stay exact, the listing truncates.
pub const MAX_CLASSES_LISTED: usize = 8192;

#[derive(Debug, Clone, serde::Serialize)]
pub struct DexInfo {
    /// "035" / "037" / "038" / "039" (from the magic `dex\n0XX\0`).
    pub version: String,
    pub checksum: u32,
    pub file_size: u32,
    pub string_count: u32,
    pub type_count: u32,
    pub proto_count: u32,
    pub field_count: u32,
    pub method_count: u32,
    pub class_count: u32,
    /// Class listing (capped at [`MAX_CLASSES_LISTED`]).
    pub classes: Vec<DexClass>,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct DexClass {
    /// Type descriptor, e.g. `Lcom/xen/demo/MainActivity;`.
    pub descriptor: String,
    pub super_descriptor: Option<String>,
    pub access_flags: u32,
    pub direct_methods: u32,
    pub virtual_methods: u32,
    /// Methods with ACC_NATIVE — the JNI surface a .so must export.
    pub native_methods: Vec<String>,
    /// Method listing for this class (name#kind), capped per class.
    pub methods: Vec<String>,
}

const ACC_PUBLIC: u32 = 0x1;
const ACC_NATIVE: u32 = 0x100;
const ACC_ABSTRACT: u32 = 0x400;
const ACC_INTERFACE: u32 = 0x200;
const ACC_SYNTHETIC: u32 = 0x1000;
const ACC_ENUM: u32 = 0x4000;

/// Human-readable access-flag string (`public static native`).
pub fn access_flags_str(flags: u32) -> String {
    let mut parts = Vec::new();
    for (bit, name) in [
        (ACC_PUBLIC, "public"),
        (0x2, "private"),
        (0x4, "protected"),
        (0x8, "static"),
        (0x10, "final"),
        (ACC_INTERFACE, "interface"),
        (ACC_ABSTRACT, "abstract"),
        (ACC_SYNTHETIC, "synthetic"),
        (ACC_ENUM, "enum"),
    ] {
        if flags & bit != 0 {
            parts.push(name);
        }
    }
    parts.join(" ")
}

/// Parse a dex blob (one classes*.dex member, raw bytes).
pub fn parse_dex(bytes: &[u8]) -> Result<DexInfo> {
    if bytes.len() > MAX_DEX_BYTES {
        bail!("dex too large ({} > cap {})", bytes.len(), MAX_DEX_BYTES);
    }
    if bytes.len() < 112 {
        bail!("dex truncated ({} bytes, header needs 112)", bytes.len());
    }
    if &bytes[..4] != b"dex\n" {
        bail!("not a dex file (magic {:?})", String::from_utf8_lossy(&bytes[..4]));
    }
    if bytes[7] != 0 {
        bail!("dex magic NUL terminator missing");
    }
    let version = String::from_utf8_lossy(&bytes[4..7]).into_owned();
    let checksum = le_u32(bytes, 8);
    let file_size = le_u32(bytes, 32);
    let endian_tag = le_u32(bytes, 40);
    if endian_tag != 0x1234_5678 {
        if endian_tag == 0x7856_3412 {
            bail!("big-endian dex (endian tag swapped) — unsupported");
        }
        bail!("bad dex endian tag {endian_tag:#010x}");
    }

    // DEX header_item layout (112 bytes): magic 0, checksum 8, signature 12,
    // file_size 32, header_size 36, endian_tag 40, link 44/48, map_off 52,
    // then size/off pairs at 56/60 (strings) through 96/100 (class defs).
    let map_off = le_u32(bytes, 52);
    let _ = map_off;
    let string_ids_size = le_u32(bytes, 56) as usize;
    let string_ids_off = le_u32(bytes, 60) as usize;
    let type_ids_size = le_u32(bytes, 64) as usize;
    let type_ids_off = le_u32(bytes, 68) as usize;
    let proto_count = le_u32(bytes, 72);
    let proto_ids_off = le_u32(bytes, 76);
    let _ = proto_ids_off;
    let field_ids_size = le_u32(bytes, 80) as usize;
    let field_ids_off = le_u32(bytes, 84) as usize;
    let _ = field_ids_off;
    let method_ids_size = le_u32(bytes, 88) as usize;
    let method_ids_off = le_u32(bytes, 92) as usize;
    let class_defs_size = le_u32(bytes, 96) as usize;
    let class_defs_off = le_u32(bytes, 100) as usize;

    // --- strings (MUTF-8) ------------------------------------------------
    let mut strings = Vec::with_capacity(string_ids_size.min(1 << 20));
    for i in 0..string_ids_size {
        let off_pos = string_ids_off + i * 4;
        if off_pos + 4 > bytes.len() {
            bail!("dex string_id {i} out of range");
        }
        let at = le_u32(bytes, off_pos) as usize;
        strings.push(read_mutf8(bytes, at).map_err(|e| anyhow::anyhow!("dex string {i}: {e}"))?);
    }

    // --- type descriptors --------------------------------------------------
    let type_desc = |bytes: &[u8], idx: u32| -> Result<String> {
        let i = idx as usize;
        if i >= type_ids_size {
            return Ok("<bad-type>".to_string());
        }
        let desc_idx = le_u32(bytes, type_ids_off + i * 4);
        Ok(strings
            .get(desc_idx as usize)
            .cloned()
            .unwrap_or_else(|| "<bad-string>".to_string()))
    };

    // --- method names ------------------------------------------------------
    let method_name = |bytes: &[u8], idx: u32| -> Option<String> {
        let i = idx as usize;
        if i >= method_ids_size {
            return None;
        }
        let at = method_ids_off + i * 8;
        if at + 8 > bytes.len() {
            return None;
        }
        let name_idx = le_u32(bytes, at + 4);
        strings.get(name_idx as usize).cloned()
    };

    // --- class defs --------------------------------------------------------
    let mut classes = Vec::with_capacity(class_defs_size.min(MAX_CLASSES_LISTED));
    for i in 0..class_defs_size {
        let at = class_defs_off + i * 32;
        if at + 32 > bytes.len() {
            bail!("dex class_def {i} out of range");
        }
        let class_idx = le_u32(bytes, at);
        let access_flags = le_u32(bytes, at + 4);
        let super_idx = le_u32(bytes, at + 8);
        let class_data_off = le_u32(bytes, at + 24);

        let mut cls = DexClass {
            descriptor: type_desc(bytes, class_idx)?,
            super_descriptor: if super_idx == 0xFFFF_FFFF {
                None
            } else {
                Some(type_desc(bytes, super_idx)?)
            },
            access_flags,
            direct_methods: 0,
            virtual_methods: 0,
            native_methods: Vec::new(),
            methods: Vec::new(),
        };

        if class_data_off != 0 {
            let cur = parse_class_data(
                bytes,
                class_data_off as usize,
                method_name,
                &mut cls,
            )?;
            let _ = cur;
        }
        if classes.len() < MAX_CLASSES_LISTED {
            classes.push(cls);
        }
    }

    Ok(DexInfo {
        version,
        checksum,
        file_size,
        string_count: string_ids_size as u32,
        type_count: type_ids_size as u32,
        proto_count,
        field_count: field_ids_size as u32,
        method_count: method_ids_size as u32,
        class_count: class_defs_size as u32,
        classes,
    })
}

/// Walk a class_data_item: uleb128 counts then encoded_field/method lists.
fn parse_class_data(
    bytes: &[u8],
    off: usize,
    method_name: impl Fn(&[u8], u32) -> Option<String>,
    cls: &mut DexClass,
) -> Result<()> {
    let mut cur = off;
    let static_fields = uleb128(bytes, &mut cur)?;
    let instance_fields = uleb128(bytes, &mut cur)?;
    let direct_methods = uleb128(bytes, &mut cur)?;
    let virtual_methods = uleb128(bytes, &mut cur)?;
    cls.direct_methods = direct_methods;
    cls.virtual_methods = virtual_methods;

    // Skip encoded_field pairs (field_idx_diff, access_flags).
    let mut skip_fields = static_fields.saturating_add(instance_fields);
    let mut fidx = 0u32;
    while skip_fields > 0 {
        skip_fields -= 1;
        fidx = fidx.wrapping_add(uleb128(bytes, &mut cur)?);
        let _flags = uleb128(bytes, &mut cur)?;
    }

    let mut midx = 0u32;
    let mut listed = 0usize;
    for _ in 0..direct_methods.saturating_add(virtual_methods) {
        midx = midx.wrapping_add(uleb128(bytes, &mut cur)?);
        let flags = uleb128(bytes, &mut cur)?;
        let _code_off = uleb128(bytes, &mut cur)?;
        if flags & ACC_NATIVE != 0 {
            if let Some(name) = method_name(bytes, midx) {
                cls.native_methods.push(name);
            }
        }
        if listed < 64 {
            if let Some(name) = method_name(bytes, midx) {
                let kind = if listed < direct_methods as usize { "direct" } else { "virtual" };
                cls.methods.push(format!("{name} ({kind})"));
                listed += 1;
            }
        }
    }
    Ok(())
}

fn uleb128(bytes: &[u8], cur: &mut usize) -> Result<u32> {
    let mut value: u32 = 0;
    let mut shift = 0u32;
    for _ in 0..5 {
        let b = *bytes
            .get(*cur)
            .ok_or_else(|| anyhow::anyhow!("uleb128 out of range at {}", cur))?;
        *cur += 1;
        value |= ((b & 0x7f) as u32) << shift;
        if b & 0x80 == 0 {
            return Ok(value);
        }
        shift += 7;
    }
    Err(anyhow::anyhow!("uleb128 longer than 5 bytes"))
}

/// MUTF-8 string_data_item: uleb128 utf16 length, modified-UTF-8 bytes, NUL.
fn read_mutf8(bytes: &[u8], at: usize) -> Result<String> {
    let mut cur = at;
    let _utf16_len = uleb128(bytes, &mut cur)? as usize;
    let mut out: Vec<u16> = Vec::with_capacity(_utf16_len);
    loop {
        let b = *bytes
            .get(cur)
            .ok_or_else(|| anyhow::anyhow!("mutf8 string out of range"))?;
        cur += 1;
        if b == 0 {
            break;
        }
        if b & 0x80 == 0 {
            out.push(b as u16);
        } else if b & 0xE0 == 0xC0 {
            let b1 = next_mutf8(bytes, &mut cur)?;
            out.push((((b & 0x1f) as u16) << 6) | (b1 & 0x3f) as u16);
        } else if b & 0xF0 == 0xE0 {
            let b1 = next_mutf8(bytes, &mut cur)?;
            let b2 = next_mutf8(bytes, &mut cur)?;
            out.push((((b & 0x0f) as u16) << 12) | (((b1 & 0x3f) as u16) << 6) | (b2 & 0x3f) as u16);
        } else {
            // MUTF-8 never uses 4-byte sequences; treat as replacement.
            out.push(0xFFFD);
        }
    }
    Ok(String::from_utf16_lossy(&out))
}

fn next_mutf8(bytes: &[u8], cur: &mut usize) -> Result<u8> {
    let b = *bytes
        .get(*cur)
        .ok_or_else(|| anyhow::anyhow!("mutf8 continuation out of range"))?;
    *cur += 1;
    Ok(b)
}

// ------------------------------------------------------------------ tests --

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// Minimal dex with 2 classes, a super class, methods (incl. one
    /// native) — assembled by hand, mirroring `dx --min-api 26` output shape.
    pub(crate) fn build_dex() -> Vec<u8> {
        // strings: 0 unused, 1.. descriptors/names in MUTF-8
        let strings = vec![
            "",                        // 0 (dex reserves index 0? not required, keep simple)
            "Lcom/xen/demo/App;",      // 1
            "Landroid/app/Activity;",  // 2
            "Lcom/xen/demo/Util;",     // 3
            "Ljava/lang/Object;",      // 4
            "onCreate",                // 5
            "attachBaseContext",       // 6
            "checksum",                // 7
            "Ljava/lang/String;",      // 8
            "V",                       // 9
            "Lcom/xen/demo/App;",      // 10 (method class ref reuses type 1)
            "javaEcho",                // 11 (native)
        ];
        let mut string_data: Vec<Vec<u8>> = Vec::new();
        for s in &strings {
            let mut d = vec![s.len() as u8]; // uleb128 utf16 len (short strings)
            d.extend_from_slice(s.as_bytes());
            d.push(0);
            string_data.push(d);
        }
        let mut types = vec![1u32, 2, 3, 4, 8]; // type_ids → descriptor idxs
        let _ = &mut types;
        let type_ids: Vec<u32> = vec![1, 2, 3, 4, 8];

        // protos: 1 proto (shorty V, return V, no params) — needed by method_ids
        // proto_id_item: shorty_idx u32, return_type_idx u32, params_off u32
        let proto_ids: Vec<[u32; 3]> = vec![[9, 9, 0]];

        // methods: (class_idx u16, proto_idx u16, name_idx u32)
        // onCreate(App), attachBaseContext(App), checksum(Util), javaEcho(App)
        let method_ids: Vec<(u16, u16, u32)> = vec![
            (0, 0, 5),  // App.onCreate
            (0, 0, 6),  // App.attachBaseContext
            (2, 0, 7),  // Util.checksum
            (0, 0, 11), // App.javaEcho (native)
        ];
        let _ = &method_ids[3];

        // class_defs: (class_idx, access, super_idx, interfaces, source,
        //              annotations, class_data_off, static_values)
        // Filled after layout.

        // ---- layout ----
        let header_size = 112usize;
        let mut off = header_size;
        let string_ids_off = off;
        off += strings.len() * 4;
        let type_ids_off = off;
        off += type_ids.len() * 4;
        let proto_ids_off = off;
        off += proto_ids.len() * 12;
        let field_ids_off = off; // 0 fields
        let method_ids_off = off;
        off += method_ids.len() * 8;
        let class_defs_off = off;
        off += 2 * 32;

        // string data blobs
        let string_data_off = off;
        let mut data_offsets = Vec::with_capacity(strings.len());
        for d in &string_data {
            data_offsets.push(off as u32);
            off += d.len();
        }

        // class data for App: 0 fields, 2 direct (onCreate non-native, attachBaseContext
        // native), 0 virtual → and Util: 1 direct (checksum)
        let mut app_class_data: Vec<u8> = Vec::new();
        uleb_put(&mut app_class_data, 0); // static_fields
        uleb_put(&mut app_class_data, 0); // instance_fields
        uleb_put(&mut app_class_data, 2); // direct_methods
        uleb_put(&mut app_class_data, 0); // virtual_methods
        uleb_put(&mut app_class_data, 0); // method idx diff (0 → onCreate)
        uleb_put(&mut app_class_data, 0x9); // public
        uleb_put(&mut app_class_data, 0); // code_off
        uleb_put(&mut app_class_data, 1); // method idx diff (→ attachBaseContext)
        uleb_put(&mut app_class_data, 0x109); // public | native
        uleb_put(&mut app_class_data, 0); // code_off

        let mut util_class_data: Vec<u8> = Vec::new();
        uleb_put(&mut util_class_data, 0);
        uleb_put(&mut util_class_data, 0);
        uleb_put(&mut util_class_data, 1);
        uleb_put(&mut util_class_data, 0);
        uleb_put(&mut util_class_data, 2); // method idx diff (→ checksum? idx 2)
        uleb_put(&mut util_class_data, 0x9);
        uleb_put(&mut util_class_data, 0);

        let app_class_data_off = off;
        off += app_class_data.len();
        let util_cd_off = off;
        off += util_class_data.len();

        let mut f = vec![0u8; off];
        // header
        f[0..4].copy_from_slice(b"dex\n");
        f[4..7].copy_from_slice(b"039");
        f[7] = 0;
        f[8..12].copy_from_slice(&0x1234_5678u32.wrapping_neg().to_le_bytes()); // checksum (fake)
        f[32..36].copy_from_slice(&(off as u32).to_le_bytes()); // file_size
        f[36..40].copy_from_slice(&112u32.to_le_bytes()); // header_size
        f[40..44].copy_from_slice(&0x1234_5678u32.to_le_bytes()); // endian tag
        f[56..60].copy_from_slice(&(strings.len() as u32).to_le_bytes()); // string_ids_size
        f[60..64].copy_from_slice(&(string_ids_off as u32).to_le_bytes());
        f[64..68].copy_from_slice(&(type_ids.len() as u32).to_le_bytes());
        f[68..72].copy_from_slice(&(type_ids_off as u32).to_le_bytes());
        f[72..76].copy_from_slice(&(proto_ids.len() as u32).to_le_bytes());
        f[76..80].copy_from_slice(&(proto_ids_off as u32).to_le_bytes());
        f[80..84].copy_from_slice(&0u32.to_le_bytes()); // field_ids_size
        f[84..88].copy_from_slice(&(field_ids_off as u32).to_le_bytes());
        f[88..92].copy_from_slice(&(method_ids.len() as u32).to_le_bytes());
        f[92..96].copy_from_slice(&(method_ids_off as u32).to_le_bytes());
        f[96..100].copy_from_slice(&2u32.to_le_bytes()); // class_defs_size
        f[100..104].copy_from_slice(&(class_defs_off as u32).to_le_bytes());

        // string_ids
        for (i, d) in data_offsets.iter().enumerate() {
            f[string_ids_off + i * 4..string_ids_off + i * 4 + 4]
                .copy_from_slice(&d.to_le_bytes());
        }
        // type_ids
        for (i, t) in type_ids.iter().enumerate() {
            f[type_ids_off + i * 4..type_ids_off + i * 4 + 4].copy_from_slice(&t.to_le_bytes());
        }
        // proto_ids
        for (i, p) in proto_ids.iter().enumerate() {
            let at = proto_ids_off + i * 12;
            f[at..at + 4].copy_from_slice(&p[0].to_le_bytes());
            f[at + 4..at + 8].copy_from_slice(&p[1].to_le_bytes());
            f[at + 8..at + 12].copy_from_slice(&p[2].to_le_bytes());
        }
        // method_ids
        for (i, m) in method_ids.iter().enumerate() {
            let at = method_ids_off + i * 8;
            f[at..at + 2].copy_from_slice(&m.0.to_le_bytes());
            f[at + 2..at + 4].copy_from_slice(&m.1.to_le_bytes());
            f[at + 4..at + 8].copy_from_slice(&m.2.to_le_bytes());
        }
        // class_defs
        let defs: [(u32, u32, u32, u32); 2] = [
            (0, 0x1, 1, app_class_data_off as u32),  // App : Activity, public
            (2, 0x1, 3, util_cd_off as u32),         // Util : Object, public
        ];
        for (i, d) in defs.iter().enumerate() {
            let at = class_defs_off + i * 32;
            f[at..at + 4].copy_from_slice(&d.0.to_le_bytes());
            f[at + 4..at + 8].copy_from_slice(&d.1.to_le_bytes());
            f[at + 8..at + 12].copy_from_slice(&d.2.to_le_bytes());
            f[at + 12..at + 16].copy_from_slice(&0u32.to_le_bytes()); // interfaces
            f[at + 16..at + 20].copy_from_slice(&0xFFFF_FFFFu32.to_le_bytes()); // source
            f[at + 20..at + 24].copy_from_slice(&0u32.to_le_bytes()); // annotations
            f[at + 24..at + 28].copy_from_slice(&d.3.to_le_bytes()); // class_data
            f[at + 28..at + 32].copy_from_slice(&0u32.to_le_bytes()); // static_values
        }
        // string data + class data bodies
        for (i, d) in string_data.iter().enumerate() {
            let at = string_data_off + data_offsets[i] as usize - string_data_off;
            f[at..at + d.len()].copy_from_slice(d);
        }
        let app_at = app_class_data_off;
        f[app_at..app_at + app_class_data.len()].copy_from_slice(&app_class_data);
        let util_at = util_cd_off;
        f[util_at..util_at + util_class_data.len()].copy_from_slice(&util_class_data);
        f
    }

    fn uleb_put(out: &mut Vec<u8>, mut v: u32) {
        loop {
            let b = (v & 0x7f) as u8;
            v >>= 7;
            if v == 0 {
                out.push(b);
                break;
            }
            out.push(b | 0x80);
        }
    }

    #[test]
    fn parses_class_inventory() {
        let dex = parse_dex(&build_dex()).expect("parses");
        assert_eq!(dex.version, "039");
        assert_eq!(dex.class_count, 2);
        assert_eq!(dex.string_count, 12);
        assert_eq!(dex.method_count, 4);
        assert_eq!(dex.classes.len(), 2);

        let app = &dex.classes[0];
        assert_eq!(app.descriptor, "Lcom/xen/demo/App;");
        assert_eq!(app.super_descriptor.as_deref(), Some("Landroid/app/Activity;"));
        assert_eq!(access_flags_str(app.access_flags), "public");
        assert_eq!(app.direct_methods, 2);
        assert_eq!(app.native_methods, vec!["attachBaseContext".to_string()]);
        assert!(app.methods.iter().any(|m| m.starts_with("onCreate (direct)")));

        let util = &dex.classes[1];
        assert_eq!(util.descriptor, "Lcom/xen/demo/Util;");
        assert_eq!(util.super_descriptor.as_deref(), Some("Ljava/lang/Object;"));
        assert_eq!(util.direct_methods, 1);
        assert!(util.native_methods.is_empty());
    }

    #[test]
    fn rejects_garbage_and_truncation() {
        assert!(parse_dex(b"nope").is_err());
        let full = build_dex();
        assert!(parse_dex(&full[..60]).is_err());
        // bad magic
        let mut bad = full.clone();
        bad[0] = b'X';
        assert!(parse_dex(&bad).is_err());
        // bad endian tag
        let mut bad = full;
        bad[40..44].copy_from_slice(&0u32.to_le_bytes());
        assert!(parse_dex(&bad).is_err());
    }
}
