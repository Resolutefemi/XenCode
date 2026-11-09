//! Minimal SVG path data parser -> tiny_skia::Path.
//! Supports M m L l H h V v C c S s Q q T t A a Z z (the lucide/lucide-static
//! subset plus the traced XenCode X outline, which uses absolute L commands).

use tiny_skia::{Path, PathBuilder, Transform};

#[derive(Clone, Copy, PartialEq)]
enum Cmd {
    MoveTo,
    LineTo,
    HLine,
    VLine,
    Cubic,
    SCubic,
    Quad,
    SQuad,
    Arc,
    Close,
}

struct Scanner<'a> {
    b: &'a [u8],
    i: usize,
}

impl<'a> Scanner<'a> {
    fn new(s: &'a str) -> Self {
        Scanner { b: s.as_bytes(), i: 0 }
    }

    fn skip_ws(&mut self) {
        while self.i < self.b.len() && matches!(self.b[self.i], b' ' | b',' | b'\n' | b'\t' | b'\r') {
            self.i += 1;
        }
    }

    fn peek_cmd(&mut self) -> Option<Cmd> {
        self.skip_ws();
        self.b.get(self.i).and_then(|&c| {
            Some(match c {
                b'M' => Cmd::MoveTo,
                b'm' => Cmd::MoveTo,
                b'L' | b'l' => Cmd::LineTo,
                b'H' | b'h' => Cmd::HLine,
                b'V' | b'v' => Cmd::VLine,
                b'C' | b'c' => Cmd::Cubic,
                b'S' | b's' => Cmd::SCubic,
                b'Q' | b'q' => Cmd::Quad,
                b'T' | b't' => Cmd::SQuad,
                b'A' | b'a' => Cmd::Arc,
                b'Z' | b'z' => Cmd::Close,
                _ => return None,
            })
        })
    }

    fn take_cmd(&mut self) -> Option<(Cmd, bool)> {
        self.skip_ws();
        let c = *self.b.get(self.i)?;
        let cmd = match c {
            b'M' => Cmd::MoveTo,
            b'm' => Cmd::MoveTo,
            b'L' => Cmd::LineTo,
            b'l' => Cmd::LineTo,
            b'H' => Cmd::HLine,
            b'h' => Cmd::HLine,
            b'V' => Cmd::VLine,
            b'v' => Cmd::VLine,
            b'C' => Cmd::Cubic,
            b'c' => Cmd::Cubic,
            b'S' => Cmd::SCubic,
            b's' => Cmd::SCubic,
            b'Q' => Cmd::Quad,
            b'q' => Cmd::Quad,
            b'T' => Cmd::SQuad,
            b't' => Cmd::SQuad,
            b'A' => Cmd::Arc,
            b'a' => Cmd::Arc,
            b'Z' | b'z' => Cmd::Close,
            _ => return None,
        };
        self.i += 1;
        Some((cmd, c.is_ascii_lowercase()))
    }

    fn number(&mut self) -> Option<f32> {
        self.skip_ws();
        let start = self.i;
        let mut j = self.i;
        if j < self.b.len() && (self.b[j] == b'-' || self.b[j] == b'+') {
            j += 1;
        }
        let mut seen_digit = false;
        let mut seen_dot = false;
        let mut seen_exp = false;
        while j < self.b.len() {
            let c = self.b[j];
            if c.is_ascii_digit() {
                seen_digit = true;
                j += 1;
            } else if c == b'.' && !seen_dot && !seen_exp {
                seen_dot = true;
                j += 1;
            } else if (c == b'e' || c == b'E') && seen_digit && !seen_exp {
                seen_exp = true;
                j += 1;
                if j < self.b.len() && (self.b[j] == b'-' || self.b[j] == b'+') {
                    j += 1;
                }
            } else {
                break;
            }
        }
        if !seen_digit {
            return None;
        }
        let s = std::str::from_utf8(&self.b[start..j]).ok()?;
        self.i = j;
        s.parse::<f32>().ok()
    }
}

/// Convert an SVG arc (endpoint parameterization) to cubic beziers.
#[allow(clippy::too_many_arguments)]
fn arc_to_cubic(
    pb: &mut PathBuilder,
    x0: f32,
    y0: f32,
    rx: f32,
    ry: f32,
    x_rot: f32,
    large: bool,
    sweep: bool,
    x: f32,
    y: f32,
) {
    if rx == 0.0 || ry == 0.0 {
        pb.line_to(x, y);
        return;
    }
    let mut rx = rx.abs();
    let mut ry = ry.abs();
    let phi = x_rot.to_radians();
    let (sin_phi, cos_phi) = phi.sin_cos();
    let dx2 = (x0 - x) / 2.0;
    let dy2 = (y0 - y) / 2.0;
    let x1p = cos_phi * dx2 + sin_phi * dy2;
    let y1p = -sin_phi * dx2 + cos_phi * dy2;

    let l = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry);
    if l > 1.0 {
        let s = l.sqrt();
        rx *= s;
        ry *= s;
    }

    let sign = if large != sweep { 1.0 } else { -1.0 };
    let num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
    let den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
    let co = sign * (if num <= 0.0 { 0.0 } else { (num / den).sqrt() });
    let cxp = co * rx * y1p / ry;
    let cyp = -co * ry * x1p / rx;

    let cx = cos_phi * cxp - sin_phi * cyp + (x0 + x) / 2.0;
    let cy = sin_phi * cxp + cos_phi * cyp + (y0 + y) / 2.0;

    let t1 = (y1p / ry).atan2(x1p / rx);

    let delta_angle = |u: (f32, f32), v: (f32, f32)| -> f32 {
        let dot = u.0 * v.0 + u.1 * v.1;
        let len = (u.0 * u.0 + u.1 * u.1).sqrt() * (v.0 * v.0 + v.1 * v.1).sqrt();
        let a = (dot / len).clamp(-1.0, 1.0).acos();
        if u.0 * v.1 - u.1 * v.0 < 0.0 {
            -a
        } else {
            a
        }
    };

    let u = (x1p / rx, y1p / ry);
    let v = (-x1p / rx, -y1p / ry);
    let mut d_theta = delta_angle(u, v);
    if !sweep && d_theta > 0.0 {
        d_theta -= std::f32::consts::PI * 2.0;
    } else if sweep && d_theta < 0.0 {
        d_theta += std::f32::consts::PI * 2.0;
    }
    let segments = ((d_theta.abs() / (std::f32::consts::PI / 2.0)).ceil() as usize).max(1);
    let delta = d_theta / segments as f32;
    let t = 4.0 / 3.0 * (delta / 4.0).tan();

    let mut a1 = t1;
    for _ in 0..segments {
        let a0 = a1;
        a1 += delta;
        let (s0, c0) = a0.sin_cos();
        let (s1, c1) = a1.sin_cos();
        let e0x = cos_phi * rx * c1 - sin_phi * ry * s1 + cx;
        let e0y = sin_phi * rx * c1 + cos_phi * ry * s1 + cy;
        let d1x = t * (-cos_phi * rx * s0 - sin_phi * ry * c0);
        let d1y = t * (-sin_phi * rx * s0 + cos_phi * ry * c0);
        let d2x = t * (cos_phi * rx * s1 + sin_phi * ry * c1);
        let d2y = t * (sin_phi * rx * s1 - cos_phi * ry * c1);
        pb.cubic_to(d1x + cx + cos_phi * rx * c0 - sin_phi * ry * s0,
                    d1y + cy + sin_phi * rx * c0 + cos_phi * ry * s0,
                    d2x + e0x, d2y + e0y, e0x, e0y);
    }
}

/// Build a tiny_skia::Path from SVG path data, applying `tr` to every point.
pub fn build(d: &str, tr: Transform) -> Option<Path> {
    let mut sc = Scanner::new(d);
    let mut pb = PathBuilder::new();
    let mut cx = 0.0f32;
    let mut cy = 0.0f32;
    let mut sx = 0.0f32;
    let mut sy = 0.0f32;
    let mut last_c = (0.0f32, 0.0f32);
    let mut last_q = (0.0f32, 0.0f32);
    let mut last_cmd = Cmd::MoveTo;

    loop {
        let (cmd, rel) = match sc.take_cmd() {
            Some(v) => v,
            None => break,
        };
        match cmd {
            Cmd::Close => {
                pb.close();
                cx = sx;
                cy = sy;
            }
            Cmd::MoveTo => {
                let mut first = true;
                loop {
                    let Some(x) = sc.number() else { break };
                    let Some(y) = sc.number() else { break };
                    let (x, y) = if rel { (cx + x, cy + y) } else { (x, y) };
                    if first {
                        pb.move_to(x, y);
                        sx = x;
                        sy = y;
                        first = false;
                    } else {
                        // SVG: coordinate pairs repeated after M are implicit LineTos
                        pb.line_to(x, y);
                    }
                    cx = x;
                    cy = y;
                    last_cmd = Cmd::MoveTo;
                }
            }
            Cmd::LineTo => loop {
                let Some(x) = sc.number() else { break };
                let Some(y) = sc.number() else { break };
                let (x, y) = if rel { (cx + x, cy + y) } else { (x, y) };
                pb.line_to(x, y);
                cx = x;
                cy = y;
                last_cmd = Cmd::LineTo;
            },
            Cmd::HLine => loop {
                let Some(x) = sc.number() else { break };
                let x = if rel { cx + x } else { x };
                pb.line_to(x, cy);
                cx = x;
                last_cmd = Cmd::HLine;
            },
            Cmd::VLine => loop {
                let Some(y) = sc.number() else { break };
                let y = if rel { cy + y } else { y };
                pb.line_to(cx, y);
                cy = y;
                last_cmd = Cmd::VLine;
            },
            Cmd::Cubic => loop {
                let Some(x1) = sc.number() else { break };
                let Some(y1) = sc.number() else { break };
                let Some(x2) = sc.number() else { break };
                let Some(y2) = sc.number() else { break };
                let Some(x) = sc.number() else { break };
                let Some(y) = sc.number() else { break };
                let (x1, y1, x2, y2, x, y) = if rel {
                    (cx + x1, cy + y1, cx + x2, cy + y2, cx + x, cy + y)
                } else {
                    (x1, y1, x2, y2, x, y)
                };
                pb.cubic_to(x1, y1, x2, y2, x, y);
                last_c = (x2, y2);
                cx = x;
                cy = y;
                last_cmd = Cmd::Cubic;
            },
            Cmd::SCubic => loop {
                let Some(x2) = sc.number() else { break };
                let Some(y2) = sc.number() else { break };
                let Some(x) = sc.number() else { break };
                let Some(y) = sc.number() else { break };
                let (x2, y2, x, y) = if rel {
                    (cx + x2, cy + y2, cx + x, cy + y)
                } else {
                    (x2, y2, x, y)
                };
                let (x1, y1) = match last_cmd {
                    Cmd::Cubic | Cmd::SCubic => (2.0 * cx - last_c.0, 2.0 * cy - last_c.1),
                    _ => (cx, cy),
                };
                pb.cubic_to(x1, y1, x2, y2, x, y);
                last_c = (x2, y2);
                cx = x;
                cy = y;
                last_cmd = Cmd::SCubic;
            },
            Cmd::Quad => loop {
                let Some(x1) = sc.number() else { break };
                let Some(y1) = sc.number() else { break };
                let Some(x) = sc.number() else { break };
                let Some(y) = sc.number() else { break };
                let (x1, y1, x, y) = if rel {
                    (cx + x1, cy + y1, cx + x, cy + y)
                } else {
                    (x1, y1, x, y)
                };
                pb.quad_to(x1, y1, x, y);
                last_q = (x1, y1);
                cx = x;
                cy = y;
                last_cmd = Cmd::Quad;
            },
            Cmd::SQuad => loop {
                let Some(x) = sc.number() else { break };
                let Some(y) = sc.number() else { break };
                let (x, y) = if rel { (cx + x, cy + y) } else { (x, y) };
                let (x1, y1) = match last_cmd {
                    Cmd::Quad | Cmd::SQuad => (2.0 * cx - last_q.0, 2.0 * cy - last_q.1),
                    _ => (cx, cy),
                };
                pb.quad_to(x1, y1, x, y);
                last_q = (x1, y1);
                cx = x;
                cy = y;
                last_cmd = Cmd::SQuad;
            },
            Cmd::Arc => loop {
                let Some(rx) = sc.number() else { break };
                let Some(ry) = sc.number() else { break };
                let Some(rot) = sc.number() else { break };
                let Some(la) = sc.number() else { break };
                let Some(sw) = sc.number() else { break };
                let Some(x) = sc.number() else { break };
                let Some(y) = sc.number() else { break };
                let (x, y) = if rel { (cx + x, cy + y) } else { (x, y) };
                arc_to_cubic(&mut pb, cx, cy, rx, ry, rot, la != 0.0, sw != 0.0, x, y);
                cx = x;
                cy = y;
                last_cmd = Cmd::Arc;
            },
        }
        if sc.peek_cmd().is_none() && sc.number().is_none() {
            break;
        }
    }

    let mut path = pb.finish()?;
    if tr != Transform::identity() {
        path = path.transform(tr)?;
    }
    Some(path)
}
