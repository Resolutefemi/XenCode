//! Frame decoding — screencast frames and screenshots land as base64 PNG or
//! JPEG; this turns them into straight RGBA buffers for the native renderer.
//!
//! The renderer composites the latest frame into the browser tab viewport
//! (Phase 6.2); the CLI `--screenshot` path writes the same buffers to disk.

use base64::Engine;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub width: u32,
    pub height: u32,
    /// Straight (non-premultiplied) RGBA, row-major.
    pub rgba: Vec<u8>,
    /// True when this frame came from the screencast stream (as opposed to
    /// an explicit `Page.captureScreenshot`).
    pub from_screencast: bool,
}

impl Frame {
    pub fn opaque(width: u32, height: u32, rgba: Vec<u8>) -> Self {
        Frame { width, height, rgba, from_screencast: false }
    }
}

#[derive(Debug, thiserror::Error)]
pub enum FrameError {
    #[error("base64 frame payload: {0}")]
    B64(#[from] base64::DecodeError),
    #[error("png frame: {0}")]
    Png(#[from] png::DecodingError),
    #[error("jpeg frame: {0}")]
    Jpeg(String),
    #[error("unknown frame format: {0}")]
    Unknown(String),
}

pub type Result<T> = std::result::Result<T, FrameError>;

/// Decode a screencast-frame `data` field (`format: "png" | "jpeg"`).
pub fn decode_frame(data_b64: &str, format: &str) -> Result<Frame> {
    let bytes = base64::engine::general_purpose::STANDARD.decode(data_b64)?;
    match format {
        "png" => decode_png(&bytes),
        "jpeg" | "jpg" => decode_jpeg(&bytes),
        other => Err(FrameError::Unknown(other.to_string())),
    }
}

/// Decode raw PNG bytes. Sniffs the signature so callers can pass payloads
/// without knowing the format.
pub fn decode_png(bytes: &[u8]) -> Result<Frame> {
    let decoder = png::Decoder::new(std::io::Cursor::new(bytes));
    let mut reader = decoder.read_info()?;
    let mut buf = vec![0u8; reader.output_buffer_size()];
    let info = reader.next_frame(&mut buf)?;
    let (w, h) = (info.width, info.height);
    let rgba = match info.color_type {
        png::ColorType::Rgba => buf[..info.buffer_size()].to_vec(),
        png::ColorType::Rgb => expand_rgb(&buf[..info.buffer_size()], w, h, info.bit_depth == png::BitDepth::Sixteen),
        png::ColorType::Grayscale => expand_gray(&buf[..info.buffer_size()], w, h),
        png::ColorType::GrayscaleAlpha => expand_gray_alpha(&buf[..info.buffer_size()], w, h),
        png::ColorType::Indexed => {
            // png crate expands palette to RGB(A) when transforms are set;
            // ask again with transformations applied.
            let mut decoder = png::Decoder::new(std::io::Cursor::new(bytes));
            decoder.set_transformations(png::Transformations::EXPAND);
            let mut reader = decoder.read_info()?;
            let mut buf2 = vec![0u8; reader.output_buffer_size()];
            let info = reader.next_frame(&mut buf2)?;
            match info.color_type {
                png::ColorType::Rgba => buf2[..info.buffer_size()].to_vec(),
                png::ColorType::Rgb => {
                    expand_rgb(&buf2[..info.buffer_size()], info.width, info.height, false)
                }
                other => return Err(FrameError::Jpeg(format!("unexpected palette target {other:?}"))),
            }
        }
    };
    Ok(Frame { width: w, height: h, rgba, from_screencast: false })
}

/// Decode raw JPEG bytes into RGBA (zune-jpeg handles the IDCT + upsampling).
pub fn decode_jpeg(bytes: &[u8]) -> Result<Frame> {
    use zune_jpeg::zune_core::colorspace::ColorSpace;
    use zune_jpeg::zune_core::options::DecoderOptions;
    use zune_jpeg::JpegDecoder;

    let opts = DecoderOptions::default().jpeg_set_out_colorspace(ColorSpace::RGBA);
    let mut decoder = JpegDecoder::new_with_options(bytes, opts);
    decoder
        .decode_headers()
        .map_err(|e| FrameError::Jpeg(e.to_string()))?;
    let (w, h) = decoder.dimensions().unwrap_or((0, 0));
    let out = decoder
        .decode()
        .map_err(|e| FrameError::Jpeg(e.to_string()))?;
    Ok(Frame { width: w as u32, height: h as u32, rgba: out, from_screencast: false })
}

fn expand_rgb(bgr: &[u8], w: u32, h: u32, sixteen: bool) -> Vec<u8> {
    let mut out = Vec::with_capacity((w * h * 4) as usize);
    if sixteen {
        // 16-bit samples → take the high byte
        for px in bgr.chunks(6) {
            if px.len() < 6 {
                break;
            }
            out.extend_from_slice(&[px[0], px[2], px[4], 255]);
        }
    } else {
        for px in bgr.chunks(3) {
            if px.len() < 3 {
                break;
            }
            out.extend_from_slice(&[px[0], px[1], px[2], 255]);
        }
    }
    out
}

fn expand_gray(g: &[u8], w: u32, h: u32) -> Vec<u8> {
    let mut out = Vec::with_capacity((w * h * 4) as usize);
    for px in g.chunks(1) {
        let v = px[0];
        out.extend_from_slice(&[v, v, v, 255]);
    }
    out
}

fn expand_gray_alpha(g: &[u8], _w: u32, _h: u32) -> Vec<u8> {
    let mut out = Vec::with_capacity(g.len() * 2);
    for px in g.chunks(2) {
        if px.len() < 2 {
            break;
        }
        let v = px[0];
        out.extend_from_slice(&[v, v, v, px[1]]);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Encode a 3×2 RGBA buffer with the png crate and decode it back.
    #[test]
    fn png_roundtrip() {
        let w = 3u32;
        let h = 2u32;
        let mut rgba = vec![0u8; (w * h * 4) as usize];
        for (i, px) in rgba.chunks_mut(4).enumerate() {
            px[0] = (i * 17) as u8;
            px[1] = (i * 31) as u8;
            px[2] = (i * 7) as u8;
            px[3] = 255;
        }
        let mut encoded = Vec::new();
        {
            let mut enc = png::Encoder::new(&mut encoded, w, h);
            enc.set_color(png::ColorType::Rgba);
            enc.set_depth(png::BitDepth::Eight);
            let mut writer = enc.write_header().unwrap();
            writer.write_image_data(&rgba).unwrap();
        }
        let frame = decode_png(&encoded).unwrap();
        assert_eq!((frame.width, frame.height), (w, h));
        assert_eq!(frame.rgba, rgba);
        assert!(!frame.from_screencast);
    }

    /// The jpeg path can't roundtrip losslessly; assert shape + opaque alpha.
    /// Encoded at test time with `jpeg-encoder` (dev-dependency) — no binary
    /// fixtures in the repo.
    #[test]
    fn jpeg_decodes_to_opaque_rgba() {
        use jpeg_encoder::{ColorType, Encoder};
        let (w, h) = (2u16, 2u16);
        // bright red, RGB (2×2 pixels = 4 triplets)
        let rgb: Vec<u8> = [200u8, 0, 0].repeat((w as usize) * (h as usize));
        let mut jpg = Vec::new();
        Encoder::new(&mut jpg, 90)
            .encode(&rgb, w, h, ColorType::Rgb)
            .expect("encode fixture jpeg");
        let frame = decode_jpeg(&jpg).unwrap();
        assert_eq!((frame.width, frame.height), (w as u32, h as u32));
        assert_eq!(frame.rgba.len(), rgb.len() * 4 / 3);
        for a in frame.rgba.chunks(4) {
            assert_eq!(a[3], 255, "jpeg frames are opaque");
        }
        // Sanity: pixels stay in the red family (chroma subsampling makes an
        // exact roundtrip impossible).
        for px in frame.rgba.chunks(4) {
            assert!(px[0] > px[1] && px[0] > px[2], "red pixel, got {px:?}");
        }
    }

    #[test]
    fn unknown_format_is_an_error() {
        let err = decode_frame("AAAA", "webp").unwrap_err();
        assert!(matches!(err, FrameError::Unknown(_)), "got {err:?}");
    }
}
