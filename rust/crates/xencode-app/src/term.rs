//! ANSI terminal grid — parses PTY output via the `vte` state machine into a
//! styled cell grid, the same job xterm.js does for the Electron renderer.
//! Scope: SGR colors (16/256/RGB, bold/italic/underline/inverse), cursor
//! movement, erase, tab, alt-screen (1049). Scroll regions arrive later with
//! the full TUI pass.

use egui::Color32;

use crate::theme;

#[derive(Clone, Copy, PartialEq)]
pub struct Cell {
    pub ch: char,
    pub fg: Color32,
    pub bg: Color32,
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub inverse: bool,
}

impl Default for Cell {
    fn default() -> Self {
        Self {
            ch: ' ',
            fg: theme::FG,
            bg: Color32::TRANSPARENT,
            bold: false,
            italic: false,
            underline: false,
            inverse: false,
        }
    }
}

fn dim_palette(i: usize) -> Color32 {
    // xterm dim 8
    [
        Color32::from_rgb(0x30, 0x30, 0x30),
        Color32::from_rgb(0xd7, 0x3d, 0x3d),
        Color32::from_rgb(0x4d, 0xb2, 0x69),
        Color32::from_rgb(0xd7, 0xc1, 0x3d),
        Color32::from_rgb(0x4a, 0x83, 0xcc),
        Color32::from_rgb(0xb8, 0x65, 0xc8),
        Color32::from_rgb(0x4d, 0xb2, 0xb2),
        Color32::from_rgb(0xcc, 0xcc, 0xcc),
    ][i]
}

fn bright_palette(i: usize) -> Color32 {
    // xterm bright 8
    [
        Color32::from_rgb(0x7a, 0x7a, 0x7a),
        Color32::from_rgb(0xff, 0x6b, 0x6b),
        Color32::from_rgb(0x6f, 0xe3, 0x8d),
        Color32::from_rgb(0xff, 0xe1, 0x6b),
        Color32::from_rgb(0x6f, 0xa8, 0xff),
        Color32::from_rgb(0xe0, 0x8f, 0xf2),
        Color32::from_rgb(0x6f, 0xe3, 0xe3),
        Color32::from_rgb(0xff, 0xff, 0xff),
    ][i]
}

fn indexed_color(i: u16) -> Color32 {
    let i = i as usize;
    match i {
        0..=7 => dim_palette(i),
        8..=15 => bright_palette(i - 8),
        16..=231 => {
            let i = i - 16;
            let (r, g, b) = ((i / 36) % 6, (i / 6) % 6, i % 6);
            let v = |x: usize| if x == 0 { 0 } else { 55 + x * 40 };
            Color32::from_rgb(v(r) as u8, v(g) as u8, v(b) as u8)
        }
        232..=255 => {
            let g = 8 + (i - 232) * 10;
            Color32::from_rgb(g as u8, g as u8, g as u8)
        }
        _ => theme::FG,
    }
}

pub struct Grid {
    pub cols: usize,
    pub rows: usize,
    pub cells: Vec<Cell>,
    pub cursor_x: usize,
    pub cursor_y: usize,
    pub scrollback: Vec<Vec<Cell>>,
    pub closed: bool,
    // current SGR state
    fg: Color32,
    bg: Color32,
    bold: bool,
    italic: bool,
    underline: bool,
    inverse: bool,
    autowrap: bool,
    // alt screen (1049)
    alt_saved: Option<(Vec<Cell>, usize, usize)>,
    in_alt: bool,
}

impl Grid {
    pub fn new(cols: usize, rows: usize) -> Self {
        Self {
            cols,
            rows,
            cells: vec![Cell::default(); cols * rows],
            cursor_x: 0,
            cursor_y: 0,
            scrollback: Vec::new(),
            closed: false,
            fg: theme::FG,
            bg: Color32::TRANSPARENT,
            bold: false,
            italic: false,
            underline: false,
            inverse: false,
            autowrap: true,
            alt_saved: None,
            in_alt: false,
        }
    }

    fn blank_row(&self) -> Vec<Cell> {
        vec![Cell::default(); self.cols]
    }

    fn scroll_up(&mut self) {
        if self.in_alt {
            // alt screen scrolls without touching scrollback
            self.cells.drain(0..self.cols);
            self.cells.extend(self.blank_row());
            return;
        }
        let top: Vec<Cell> = self.cells[..self.cols].to_vec();
        if self.scrollback.len() >= 1000 {
            self.scrollback.remove(0);
        }
        self.scrollback.push(top);
        self.cells.drain(0..self.cols);
        self.cells.extend(self.blank_row());
    }

    fn linefeed(&mut self) {
        self.cursor_y += 1;
        if self.cursor_y >= self.rows {
            self.cursor_y = self.rows - 1;
            self.scroll_up();
        }
    }

    fn put_char(&mut self, c: char) {
        if self.cursor_x >= self.cols {
            if self.autowrap {
                self.cursor_x = 0;
                self.linefeed();
            } else {
                self.cursor_x = self.cols - 1;
            }
        }
        let idx = self.cursor_y * self.cols + self.cursor_x;
        let cell_fg = if self.inverse {
            self.bg_or_default()
        } else {
            self.fg
        };
        let cell_bg = if self.inverse { self.fg } else { self.bg };
        if let Some(cell) = self.cells.get_mut(idx) {
            *cell = Cell {
                ch: c,
                fg: cell_fg,
                bg: cell_bg,
                bold: self.bold,
                italic: self.italic,
                underline: self.underline,
                inverse: false,
            };
        }
        self.cursor_x += 1;
    }

    fn bg_or_default(&self) -> Color32 {
        if self.bg == Color32::TRANSPARENT {
            theme::NEUTRAL_950
        } else {
            self.bg
        }
    }

    fn erase_in_display(&mut self, mode: u16) {
        match mode {
            0 => {
                self.erase_in_line(0);
                let start = (self.cursor_y + 1) * self.cols;
                for cell in self.cells.iter_mut().skip(start) {
                    *cell = Cell::default();
                }
            }
            1 => {
                let end = self.cursor_y * self.cols + self.cursor_x.min(self.cols - 1) + 1;
                for cell in self.cells.iter_mut().take(end) {
                    *cell = Cell::default();
                }
            }
            _ => {
                for cell in self.cells.iter_mut() {
                    *cell = Cell::default();
                }
                self.cursor_x = 0;
                self.cursor_y = 0;
            }
        }
    }

    fn erase_in_line(&mut self, mode: u16) {
        let row = self.cursor_y * self.cols;
        match mode {
            0 => {
                let end = (row + self.cursor_x).min(row + self.cols - 1);
                for cell in self.cells.iter_mut().take(row + self.cols).skip(end) {
                    *cell = Cell::default();
                }
            }
            1 => {
                let end = (row + self.cursor_x + 1).min(row + self.cols);
                for cell in self.cells.iter_mut().take(end).skip(row) {
                    *cell = Cell::default();
                }
            }
            _ => {
                for cell in self.cells.iter_mut().take(row + self.cols).skip(row) {
                    *cell = Cell::default();
                }
            }
        }
    }

    pub fn resize(&mut self, cols: usize, rows: usize) {
        if cols == self.cols && rows == self.rows {
            return;
        }
        let mut next = vec![Cell::default(); cols * rows];
        let copy_rows = self.rows.min(rows);
        let copy_cols = self.cols.min(cols);
        for r in 0..copy_rows {
            let src = r * self.cols;
            let dst = r * cols;
            next[dst..dst + copy_cols].copy_from_slice(&self.cells[src..src + copy_cols]);
        }
        self.cells = next;
        self.cols = cols;
        self.rows = rows;
        self.cursor_x = self.cursor_x.min(cols - 1);
        self.cursor_y = self.cursor_y.min(rows - 1);
    }

    fn apply_sgr(&mut self, params: &[u16]) {
        let mut i = 0;
        while i < params.len() {
            match params[i] {
                0 => {
                    self.fg = theme::FG;
                    self.bg = Color32::TRANSPARENT;
                    self.bold = false;
                    self.italic = false;
                    self.underline = false;
                    self.inverse = false;
                }
                1 => self.bold = true,
                2 => {} // dim (approximate by nothing)
                3 => self.italic = true,
                4 => self.underline = true,
                7 => self.inverse = true,
                22 | 23 | 24 | 27 => {
                    self.bold = false;
                    self.italic = false;
                    self.underline = false;
                    self.inverse = false;
                }
                30..=37 => {
                    self.fg = dim_palette((params[i] - 30) as usize);
                    if self.bold {
                        // bold dim colors render as their bright variants
                        self.fg = bright_palette((params[i] - 30) as usize);
                    }
                }
                38 => {
                    if i + 1 < params.len() && params[i + 1] == 5 {
                        if i + 2 < params.len() {
                            self.fg = indexed_color(params[i + 2]);
                            i += 2;
                        }
                    } else if i + 1 < params.len() && params[i + 1] == 2 && i + 4 < params.len() {
                        self.fg = Color32::from_rgb(
                            params[i + 2] as u8,
                            params[i + 3] as u8,
                            params[i + 4] as u8,
                        );
                        i += 4;
                    }
                }
                39 => self.fg = theme::FG,
                40..=47 => self.bg = dim_palette((params[i] - 40) as usize),
                48 => {
                    if i + 1 < params.len() && params[i + 1] == 5 {
                        if i + 2 < params.len() {
                            self.bg = indexed_color(params[i + 2]);
                            i += 2;
                        }
                    } else if i + 1 < params.len() && params[i + 1] == 2 && i + 4 < params.len() {
                        self.bg = Color32::from_rgb(
                            params[i + 2] as u8,
                            params[i + 3] as u8,
                            params[i + 4] as u8,
                        );
                        i += 4;
                    }
                }
                49 => self.bg = Color32::TRANSPARENT,
                90..=97 => self.fg = bright_palette((params[i] - 90) as usize),
                100..=107 => self.bg = bright_palette((params[i] - 100) as usize),
                _ => {}
            }
            i += 1;
        }
    }

    fn enter_alt(&mut self) {
        if !self.in_alt {
            self.in_alt = true;
            self.alt_saved = Some((self.cells.clone(), self.cursor_x, self.cursor_y));
            self.cells = vec![Cell::default(); self.cols * self.rows];
            self.cursor_x = 0;
            self.cursor_y = 0;
        }
    }

    fn exit_alt(&mut self) {
        if self.in_alt {
            if let Some((cells, x, y)) = self.alt_saved.take() {
                self.cells = cells;
                self.cursor_x = x;
                self.cursor_y = y;
            }
            self.in_alt = false;
        }
    }
}

impl vte::Perform for Grid {
    fn print(&mut self, c: char) {
        self.put_char(c);
    }

    fn execute(&mut self, byte: u8) {
        match byte {
            b'\r' => self.cursor_x = 0,
            b'\n' | 0x0b | 0x0c => self.linefeed(),
            0x08 => self.cursor_x = self.cursor_x.saturating_sub(1),
            b'\t' => {
                let next = ((self.cursor_x / 8) + 1) * 8;
                self.cursor_x = next.min(self.cols - 1);
            }
            _ => {}
        }
    }

    fn csi_dispatch(
        &mut self,
        params: &vte::Params,
        _intermediates: &[u8],
        _ignore: bool,
        action: char,
    ) {
        let flat: Vec<u16> = params.iter().flat_map(|s| s.iter().copied()).collect();
        let p = |idx: usize, default: u16| -> u16 {
            flat.get(idx)
                .copied()
                .filter(|&v| v != 0)
                .unwrap_or(default)
        };
        match action {
            'A' => self.cursor_y = self.cursor_y.saturating_sub(p(0, 1) as usize),
            'B' => self.cursor_y = (self.cursor_y + p(0, 1) as usize).min(self.rows - 1),
            'C' => self.cursor_x = (self.cursor_x + p(0, 1) as usize).min(self.cols - 1),
            'D' => self.cursor_x = self.cursor_x.saturating_sub(p(0, 1) as usize),
            'E' => self.cursor_y = (self.cursor_y + p(0, 1) as usize).min(self.rows - 1),
            'F' => self.cursor_y = self.cursor_y.saturating_sub(p(0, 1) as usize),
            'G' => self.cursor_x = (p(0, 1).max(1) as usize - 1).min(self.cols - 1),
            'H' | 'f' => {
                self.cursor_y = (p(0, 1).max(1) as usize - 1).min(self.rows - 1);
                self.cursor_x = (p(1, 1).max(1) as usize - 1).min(self.cols - 1);
            }
            'J' => self.erase_in_display(flat.first().copied().unwrap_or(0)),
            'K' => self.erase_in_line(flat.first().copied().unwrap_or(0)),
            'm' => {
                if flat.is_empty() {
                    self.apply_sgr(&[0]);
                } else {
                    self.apply_sgr(&flat);
                }
            }
            'h' => {
                if flat.contains(&1049) {
                    self.enter_alt();
                }
            }
            'l' => {
                if flat.contains(&1049) {
                    self.exit_alt();
                }
            }
            'r' => {} // DECSTBM: full TUI pass
            's' => {} // save cursor
            'u' => {} // restore cursor
            _ => {}
        }
    }

    fn esc_dispatch(&mut self, _intermediates: &[u8], _ignore: bool, byte: u8) {
        if byte == b'c' {
            // RIS — full reset
            for cell in self.cells.iter_mut() {
                *cell = Cell::default();
            }
            self.cursor_x = 0;
            self.cursor_y = 0;
            self.fg = theme::FG;
            self.bg = Color32::TRANSPARENT;
            self.bold = false;
            self.italic = false;
            self.underline = false;
            self.inverse = false;
        }
    }
}

/// Parser + grid pair shared with the PTY reader thread.
pub struct TermCore {
    pub parser: vte::Parser,
    pub grid: Grid,
}

impl TermCore {
    pub fn new(cols: usize, rows: usize) -> Self {
        Self {
            parser: vte::Parser::new(),
            grid: Grid::new(cols, rows),
        }
    }

    pub fn feed(&mut self, bytes: &[u8]) {
        for &b in bytes {
            let Self { parser, grid } = self;
            parser.advance(grid, b);
        }
    }
}
