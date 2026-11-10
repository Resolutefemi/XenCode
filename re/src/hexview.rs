//! [`hexview`] — the hex-view data model: rows of offset / hex / ASCII.
//!
//! Pure formatting, no GUI: the same rows feed the CLI (`xencode re hex`)
//! and the native hex viewer panel (Phase 7 GUI wave), so both surfaces
//! show byte-identical output. Offsets are absolute file offsets; rows are
//! clamped to the available bytes.

use serde::{Deserialize, Serialize};

/// Default row width (16 bytes — the standard hex-view geometry).
pub const DEFAULT_ROW_WIDTH: usize = 16;

/// One rendered row: `offset  bb bb … bb  |ascii|`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HexRow {
    /// Absolute file offset of the row's first byte.
    pub offset: u64,
    /// Bytes in this row (≤ row width — short tail rows allowed).
    pub bytes: Vec<u8>,
}

/// Split `data` (already sliced to the requested range) starting at
/// `start_offset` into rows of `width` bytes.
pub fn hex_rows(data: &[u8], start_offset: u64, width: usize) -> Vec<HexRow> {
    let width = width.max(1);
    data.chunks(width)
        .enumerate()
        .map(|(i, bytes)| HexRow {
            offset: start_offset + (i * width) as u64,
            bytes: bytes.to_vec(),
        })
        .collect()
}

/// The ASCII gutter for a row: printable bytes as-is, the rest as `.`.
pub fn ascii_gutter(bytes: &[u8]) -> String {
    bytes
        .iter()
        .map(|&b| {
            if (0x20..=0x7e).contains(&b) {
                b as char
            } else {
                '.'
            }
        })
        .collect()
}

/// Format one row: `00001000  48 8b 05 c8 …  |H………|` with the hex block
/// padded to `width` so gutters align across short tail rows.
pub fn render_hex_row(row: &HexRow, width: usize) -> String {
    let width = width.max(1);
    let hex: Vec<String> = row.bytes.iter().map(|b| format!("{b:02x}")).collect();
    let mut line = String::new();
    line.push_str(&format!("{:08x}  ", row.offset));
    line.push_str(&hex.join(" "));
    // Pad short rows so the ASCII gutter stays column-aligned.
    for _ in row.bytes.len()..width {
        line.push_str("   ");
    }
    line.push_str("  |");
    let gutter = ascii_gutter(&row.bytes);
    line.push_str(&gutter);
    for _ in gutter.chars().count()..width {
        line.push(' ');
    }
    line.push('|');
    line
}

/// Clamp a requested byte range against a buffer length: (offset, length).
pub fn clamp_range(offset: u64, len: u64, total: u64) -> (u64, u64) {
    let offset = offset.min(total);
    let len = len.min(total - offset);
    (offset, len)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Vec<u8> {
        // 3 rows worth: "Hello World" + zeros + 0x7f/0x80 noise.
        let mut v = b"Hello World".to_vec();
        v.extend(vec![0u8; 16]);
        v.extend([0x7f, 0x80, 0x41, 0x42]);
        v
    }

    #[test]
    fn rows_chunk_and_offset_correctly() {
        let data = sample();
        let rows = hex_rows(&data, 0x1000, 16);
        assert_eq!(rows.len(), 2); // 31 bytes → 2 rows of 16/15
        assert_eq!(rows[0].offset, 0x1000);
        assert_eq!(rows[0].bytes.len(), 16);
        assert_eq!(rows[1].offset, 0x1010);
        assert_eq!(rows[1].bytes.len(), 15);
    }

    #[test]
    fn render_aligns_gutter_across_rows() {
        let data = sample();
        let rows = hex_rows(&data, 0, 16);
        let r0 = render_hex_row(&rows[0], 16);
        let r1 = render_hex_row(&rows[1], 16);
        let pipe0 = r0.rfind('|').unwrap();
        let pipe1 = r1.rfind('|').unwrap();
        assert_eq!(pipe0, pipe1, "ASCII gutters must align");
        assert!(r0.starts_with("00000000  48 65 6c 6c 6f"));
        assert!(r0.contains("|Hello World"));
        assert!(r0.contains("....."), "zeros render as dots");
    }

    #[test]
    fn gutter_printable_vs_noise() {
        assert_eq!(ascii_gutter(b"A\x00\xff~"), "A..~");
    }

    #[test]
    fn clamp_keeps_ranges_inside_the_buffer() {
        assert_eq!(clamp_range(10, 20, 25), (10, 15));
        assert_eq!(clamp_range(30, 20, 25), (25, 0));
        assert_eq!(clamp_range(0, 5, 25), (0, 5));
    }

    #[test]
    fn empty_input_gives_no_rows() {
        assert!(hex_rows(&[], 0, 16).is_empty());
    }
}
