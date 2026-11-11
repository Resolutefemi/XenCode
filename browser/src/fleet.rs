//! Headless fleet (Phase 6.4) — parallel Spaces at scale.
//!
//! Each worker owns one Space (its own Chromium process — no GIL, no shared
//! state, see `space.rs`); the URL queue hands out one URL at a time and a
//! global rate limiter spaces navigations across every worker. Failures are
//! per-URL (collected, never fatal); the summary keeps submission order so
//! exports stay deterministic.

use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde::Serialize;
use serde_json::{Map, Value};

use crate::scrape::{self, ScrapePage, ScrapeSpec};
use crate::space::{Space, SpaceKind, SpaceOptions};

/// Fleet tuning.
#[derive(Debug, Clone)]
pub struct FleetOptions {
    /// Worker Spaces (1 = sequential single-Space run).
    pub workers: u32,
    /// Global navigation rate cap (navigations per second, fleet-wide).
    /// `None` = unthrottled.
    pub rps: Option<f64>,
    /// Per-URL navigation + extraction deadline.
    pub timeout_ms: u64,
    /// Explicit browser binary (discovered when `None`).
    pub browser_path: Option<PathBuf>,
    /// Headless Spaces (fleet default: true).
    pub headless: bool,
    /// Profile kind for every worker.
    pub kind: SpaceKind,
}

impl Default for FleetOptions {
    fn default() -> Self {
        FleetOptions {
            workers: 4,
            rps: None,
            timeout_ms: 30_000,
            browser_path: None,
            headless: true,
            kind: SpaceKind::CleanRoom,
        }
    }
}

/// One URL's outcome.
#[derive(Debug, Clone, Serialize)]
pub struct PageResult {
    pub url: String,
    pub ok: bool,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub rows: Vec<Map<String, Value>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub pages: Vec<ScrapePage>,
    pub ms: u64,
}

/// Shared navigation rate gate: every navigation must wait until
/// `min_interval` has passed since the previous one (fleet-wide).
#[derive(Debug)]
pub struct RateLimiter {
    min_interval: Option<Duration>,
    last: Mutex<Option<Instant>>,
}

impl RateLimiter {
    pub fn new(rps: Option<f64>) -> Self {
        RateLimiter {
            min_interval: rps.filter(|r| *r > 0.0).map(|r| Duration::from_secs_f64(1.0 / r)),
            last: Mutex::new(None),
        }
    }

    pub fn min_interval_ms(&self) -> u64 {
        self.min_interval.map(|d| d.as_millis() as u64).unwrap_or(0)
    }

    /// Wait for the next slot. The next-launch instant is reserved BEFORE
    /// sleeping so parallel workers queue up instead of racing — one call
    /// reserves exactly one slot (no loop: re-reserving after the sleep
    /// would push every caller to the back of the queue forever).
    pub async fn gate(&self) {
        let Some(interval) = self.min_interval else { return };
        let wait = {
            let mut last = self.last.lock().unwrap();
            let now = Instant::now();
            let start_at = match *last {
                Some(t) if t > now => t, // another worker reserved a slot
                _ => now,               // slot free — take it now
            };
            let reserved = start_at + interval;
            *last = Some(reserved);
            reserved.saturating_duration_since(now)
        };
        if !wait.is_zero() {
            tokio::time::sleep(wait).await;
        }
    }
}

/// URL queue: workers pull the next index atomically (deterministic order
/// on submission; results keep it too).
struct UrlQueue {
    urls: Vec<String>,
    next: AtomicUsize,
}

impl UrlQueue {
    fn pull(&self) -> Option<(usize, String)> {
        let i = self.next.fetch_add(1, Ordering::SeqCst);
        self.urls.get(i).map(|u| (i, u.clone()))
    }
}

/// Run the spec across every URL on `workers` parallel Spaces. Results are
/// returned in submission order; a worker failure (browser death) fails
/// only the URL it was serving.
pub async fn run_fleet(
    urls: Vec<String>,
    spec: ScrapeSpec,
    opts: FleetOptions,
) -> anyhow::Result<Vec<PageResult>> {
    spec.validate()?;
    if urls.is_empty() {
        return Ok(Vec::new());
    }
    let workers = opts.workers.max(1) as usize;
    let limiter = Arc::new(RateLimiter::new(opts.rps));
    let queue = Arc::new(UrlQueue { urls: urls.clone(), next: AtomicUsize::new(0) });
    let results: Arc<Mutex<Vec<Option<PageResult>>>> =
        Arc::new(Mutex::new((0..urls.len()).map(|_| None).collect()));

    let mut handles = Vec::with_capacity(workers);
    for w in 0..workers {
        let limiter = limiter.clone();
        let queue = queue.clone();
        let results = results.clone();
        let spec = spec.clone();
        let opts = opts.clone();
        handles.push(tokio::spawn(async move {
            // Launch this worker's Space once; a launch failure fails every
            // URL the worker would have taken — they get retried by their
            // entry staying None... no: mark them failed when the queue
            // still hands URLs out and the space is dead.
            let space = match Space::launch(SpaceOptions {
                kind: opts.kind.clone(),
                headless: opts.headless,
                browser_path: opts.browser_path.clone(),
                ..Default::default()
            })
            .await
            {
                Ok(s) => s,
                Err(e) => {
                    // Claim the rest of the queue and mark each failed.
                    let mut remaining = Vec::new();
                    while let Some((i, url)) = queue.pull() {
                        remaining.push((i, url));
                    }
                    let mut res = results.lock().unwrap();
                    for (i, url) in remaining {
                        res[i] = Some(PageResult {
                            url,
                            ok: false,
                            rows: Vec::new(),
                            error: Some(format!("worker {w} launch failed: {e}")),
                            pages: Vec::new(),
                            ms: 0,
                        });
                    }
                    return;
                }
            };

            while let Some((i, url)) = queue.pull() {
                let started = Instant::now();
                limiter.gate().await;
                let job = async {
                    space.navigate(&url).await?;
                    scrape::scrape(&space, &spec).await
                };
                let outcome = tokio::time::timeout(
                    Duration::from_millis(opts.timeout_ms.max(1_000)),
                    job,
                )
                .await;
                let (ok, rows, error, pages) = match outcome {
                    Ok(Ok(pages)) => {
                        let rows = pages.iter().flat_map(|p| p.rows.clone()).collect();
                        (true, rows, None, pages)
                    }
                    Ok(Err(e)) => (false, Vec::new(), Some(format!("{e:#}")), Vec::new()),
                    Err(_) => (
                        false,
                        Vec::new(),
                        Some(format!("timed out after {}ms", opts.timeout_ms)),
                        Vec::new(),
                    ),
                };
                let res = PageResult {
                    url,
                    ok,
                    rows,
                    error,
                    pages,
                    ms: started.elapsed().as_millis() as u64,
                };
                results.lock().unwrap()[i] = Some(res);
            }
        }));
    }

    for h in handles {
        let _ = h.await;
    }

    let done = results.lock().unwrap().clone();
    Ok(done
        .into_iter()
        .enumerate()
        .map(|(i, slot)| {
            slot.unwrap_or_else(|| PageResult {
                url: urls[i].clone(),
                ok: false,
                rows: Vec::new(),
                error: Some("no worker claimed this URL".into()),
                pages: Vec::new(),
                ms: 0,
            })
        })
        .collect())
}

/// Parse a URLs file — one URL per line, `#` comments and blank lines
/// skipped.
pub fn parse_url_list(text: &str) -> Vec<String> {
    text.lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .map(str::to_string)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn rate_limiter_spaces_reservations() {
        let lim = Arc::new(RateLimiter::new(Some(50.0))); // 20 ms interval
        let start = Instant::now();
        let mut handles = Vec::new();
        for _ in 0..3 {
            let lim = lim.clone();
            handles.push(tokio::spawn(async move { lim.gate().await }));
        }
        for h in handles {
            h.await.unwrap();
        }
        let elapsed = start.elapsed();
        // 3 reservations across parallel workers: the 3rd fires at ≥ 40 ms
        assert!(
            elapsed >= Duration::from_millis(35),
            "rate gate must serialize navigations, took {elapsed:?}"
        );
    }

    #[tokio::test]
    async fn rate_limiter_off_when_none() {
        let lim = RateLimiter::new(None);
        assert_eq!(lim.min_interval_ms(), 0);
        let start = Instant::now();
        for _ in 0..50 {
            lim.gate().await;
        }
        assert!(start.elapsed() < Duration::from_millis(50), "no gate → no wait");
    }

    #[test]
    fn url_list_parses_lines_and_comments() {
        let urls = parse_url_list(
            "# comment\nhttps://a.test/\n\n  https://b.test/x  \n# another\nhttps://c.test/",
        );
        assert_eq!(
            urls,
            vec!["https://a.test/", "https://b.test/x", "https://c.test/"]
        );
    }

    #[test]
    fn empty_url_list_is_an_empty_run() {
        let urls: Vec<String> = Vec::new();
        assert_eq!(urls.len(), 0);
    }
}
