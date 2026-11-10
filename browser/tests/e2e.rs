//! End-to-end tests against a real Chromium.
//!
//! These tests launch a real headless browser through the production path
//! (discovery → pipe spawn → CDP). They skip cleanly (early return) when no
//! browser is installed, so `cargo test` stays green everywhere — and fully
//! exercises the stack where a browser exists (this repo's dev sandbox
//! ships a Playwright Chromium, which `discover` finds automatically).

use std::sync::Mutex;
use std::time::Duration;

use xencode_browser::{discover, Selector, Space, SpaceKind, SpaceOptions};

fn have_browser() -> Option<std::path::PathBuf> {
    discover::find_browser().map(|c| c.path)
}

/// Real Chromium instances are heavyweight; running three e2e suites in
/// parallel can OOM the sandbox (Chrome dies mid-attach → pipe EOF). One
/// browser at a time keeps the suite deterministic — parallel *Spaces*
/// stay available to real users (see `e2e_two_spaces_are_isolated`, which
/// runs two under the lock).
static BROWSER_LOCK: Mutex<()> = Mutex::new(());

async fn clean_space() -> anyhow::Result<Space> {
    Space::launch(SpaceOptions {
        kind: SpaceKind::CleanRoom,
        headless: true,
        ..Default::default()
    })
    .await
}

fn fixture_page(dir: &std::path::Path) -> std::path::PathBuf {
    let page = dir.join("page.html");
    std::fs::write(
        &page,
        r#"<!doctype html>
<html>
<head><title>XenBrowser e2e</title></head>
<body>
  <nav id="top"><a href="/docs">Docs</a></nav>
  <main>
    <h1>Counter</h1>
    <p id="count">0 items</p>
    <button id="add" onclick="bump()">Add</button>
    <input id="name" placeholder="Your name" value=""/>
    <script>
      let n = 0;
      function bump() { n += 1; document.getElementById('count').textContent = n + (n === 1 ? ' item' : ' items'); }
    </script>
  </main>
</body>
</html>"#,
    )
    .unwrap();
    page
}

#[tokio::test]
async fn e2e_navigate_snapshot_click_screenshot() {
    let _browser_slot = BROWSER_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let Some(browser) = have_browser() else {
        eprintln!("skipping: no browser found");
        return;
    };
    eprintln!("using browser: {}", browser.display());

    let dir = tempfile::tempdir().unwrap();
    let page = fixture_page(dir.path());
    let url = format!("file://{}", page.display());

    let space = clean_space().await.expect("launch space");

    // Navigation + basic document properties.
    space.navigate(&url).await.expect("navigate");
    assert_eq!(space.title().await.unwrap(), "XenBrowser e2e");
    assert!(space.url().await.unwrap().starts_with("file://"));

    // Snapshot outline contains the meaningful bits.
    let (outline, truncated) = space.outline().await.expect("outline");
    assert!(!truncated, "tiny page must not truncate");
    assert!(outline.contains("h1 \"Counter\""), "outline:\n{outline}");
    assert!(outline.contains("p id=count \"0 items\""), "outline:\n{outline}");
    assert!(outline.contains("a href=/docs \"Docs\""), "outline:\n{outline}");
    assert!(outline.contains("input"), "outline:\n{outline}");
    assert!(!outline.contains("<script>"), "script lines are skipped");

    // Click the Add button via a text selector; the counter increments.
    space.click(&Selector::Text("Add".into())).await.expect("click Add");
    let count = space
        .evaluate("document.getElementById('count').textContent")
        .await
        .unwrap();
    assert_eq!(count, "1 item", "click landed");

    // Fill the input; the value reflects typed text.
    space
        .fill(&Selector::Css("#name".into()), "Femi")
        .await
        .expect("fill");
    let val = space.evaluate("document.getElementById('name').value").await.unwrap();
    assert_eq!(val, "Femi");

    // Press Enter into the input (focus + key events round-trip).
    space.press("Enter").await.expect("press");

    // Screenshot decodes to a real frame.
    let shot = space.screenshot().await.expect("screenshot");
    assert!(shot.width >= 800, "viewport-sized frame, got {}", shot.width);
    assert_eq!(shot.rgba.len(), (shot.width * shot.height * 4) as usize);

    // Snapshot diff: click once more, then diff — only the counter changed.
    let before = space.snapshot().await.unwrap();
    space.click(&Selector::Css("#add".into())).await.expect("click #add");
    let diff = space.diff_since(&before).await.expect("diff");
    assert!(!diff.is_empty());
    assert!(diff.changed.iter().any(|l| l.line.contains("item")), "{diff:?}");
    // exactly one changed node, nothing added/removed
    assert_eq!(diff.changed.len(), 1, "{diff:?}");
    assert_eq!(diff.added.len(), 0, "{diff:?}");
    assert_eq!(diff.removed.len(), 0, "{diff:?}");

    drop(space);
}

#[tokio::test]
async fn e2e_screencast_frames_flow() {
    let _browser_slot = BROWSER_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let Some(_) = have_browser() else {
        eprintln!("skipping: no browser found");
        return;
    };
    let dir = tempfile::tempdir().unwrap();
    let page = fixture_page(dir.path());
    let url = format!("file://{}", page.display());

    let space = clean_space().await.expect("launch space");
    space.navigate(&url).await.expect("navigate");

    let mut cast = space.start_screencast().await.expect("start screencast");
    let frame = cast
        .wait_for_frame(Duration::from_secs(10))
        .await
        .expect("at least one screencast frame within 10s");
    assert!(frame.width > 0 && frame.height > 0);
    assert!(frame.from_screencast, "screencast frames are flagged");
    assert_eq!(frame.rgba.len(), (frame.width * frame.height * 4) as usize);
    drop(cast);
    drop(space);
}

#[tokio::test]
async fn e2e_two_spaces_are_isolated() {
    let _browser_slot = BROWSER_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    if have_browser().is_none() {
        eprintln!("skipping: no browser found");
        return;
    }
    // Two parallel clean-room Spaces: independent documents, same fixture.
    let dir = tempfile::tempdir().unwrap();
    let page = fixture_page(dir.path());
    let url = format!("file://{}", page.display());

    let (a, b) = tokio::join!(clean_space(), clean_space());
    let a = a.expect("space a");
    let b = b.expect("space b");
    let (ra, rb) = tokio::join!(a.navigate(&url), b.navigate(&url));
    ra.expect("space a navigate");
    rb.expect("space b navigate");

    a.click(&Selector::Text("Add".into())).await.unwrap();
    let ca = a.evaluate("document.getElementById('count').textContent").await.unwrap();
    let cb = b.evaluate("document.getElementById('count').textContent").await.unwrap();
    assert_eq!(ca, "1 item");
    assert_eq!(cb, "0 items", "spaces must not share state");
}

/// Phase 8 e2e — network capture → endpoint catalog → copy-as → OpenAPI.
/// A local CORS-enabled JSON API serves `GET /api/items?page=2`; the page
/// fetch()es it from Chromium; the CDP Network pump must turn that into a
/// redacted NetEntry, then into a catalog endpoint with an inferred
/// response shape, a working curl snippet and an OpenAPI path.
#[tokio::test]
async fn e2e_network_capture_endpoint_catalog() {
    use xencode_browser::{build_catalog, copy_as, diff_catalogs, to_openapi, CaptureOptions,
        CaptureFile, CopyAs, NetCapture};

    let _browser_slot = BROWSER_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let Some(browser) = have_browser() else {
        eprintln!("skipping: no browser found");
        return;
    };
    eprintln!("using browser: {}", browser.display());

    // Local JSON API (single-shot connection server).
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    std::thread::spawn(move || {
        use std::io::{Read, Write};
        for stream in listener.incoming() {
            let Ok(mut s) = stream else { break };
            let _ = s.set_read_timeout(Some(std::time::Duration::from_secs(2)));
            let _ = Read::read(&mut s, &mut vec![0u8; 16 * 1024]);
            let body = br#"{"ok":true,"items":[1,2,3],"name":"xen"}"#;
            let head = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                body.len()
            );
            let _ = s.write_all(head.as_bytes());
            let _ = s.write_all(body);
        }
    });

    let dir = tempfile::tempdir().unwrap();
    let page = dir.path().join("page.html");
    std::fs::write(
        &page,
        format!(
            r#"<!doctype html><html><head><title>XenCapture</title></head><body><script>
              fetch('http://127.0.0.1:{port}/api/items?page=2').then(r => r.json()).catch(() => {{}});
            </script></body></html>"#
        ),
    )
    .unwrap();
    let url = format!("file://{}", page.display());

    let space = clean_space().await.expect("launch space");
    let capture = NetCapture::start(&space, CaptureOptions::default())
        .await
        .expect("start capture");
    space.navigate(&url).await.expect("navigate");

    // Give the fetch time to fire and complete (auto-wait lives in the
    // actions; here the page drives itself, so wait a fixed settle).
    tokio::time::sleep(Duration::from_millis(2000)).await;

    let entries = capture.stop().await.expect("stop capture");

    let mut hit = None;
    for e in &entries {
        if e.url.contains("/api/items") {
            hit = Some(e.clone());
        }
    }
    let hit = hit.expect("fetch() to the local API must be captured");
    assert_eq!(hit.method, "GET");
    assert_eq!(hit.status, Some(200));
    assert!(
        hit.query.iter().any(|(k, v)| k == "page" && v == "2"),
        "query pairs: {:?}",
        hit.query
    );
    let body = hit.body.as_deref().unwrap_or("");
    assert!(body.contains("\"ok\":true") || body.contains("\"ok\": true"), "body: {body}");
    // Secret redaction: no such header here, but the mechanism is unit-tested.

    // Catalog: templated endpoint with an inferred response shape.
    let cat = build_catalog(&entries);
    let ep = cat
        .endpoints
        .iter()
        .find(|e| e.path == "/api/items")
        .expect("endpoint in catalog");
    assert_eq!(ep.method, "GET");
    assert!(ep.response_schema.is_some());
    let brief = xencode_browser::schema_brief(ep.response_schema.as_ref().unwrap());
    assert!(brief.contains("ok: boolean"), "brief: {brief}");

    // Copy-as curl replays the concrete URL.
    let curl = copy_as(ep, CopyAs::Curl);
    assert!(curl.contains(&format!("'http://127.0.0.1:{port}/api/items?page=2'")), "{curl}");

    // OpenAPI export carries the path + operation.
    let doc = to_openapi(&cat, "e2e observed");
    assert!(doc["paths"]["/api/items"]["get"].is_object());

    // Monitor: same traffic again → no changes.
    let cat2 = build_catalog(&entries);
    let d = diff_catalogs(&cat, &cat2);
    assert!(!d.has_changes());

    // Capture artifact round-trip.
    let out = dir.path().join("capture.json");
    let file = CaptureFile::new(&url, true, entries);
    file.save(&out).unwrap();
    let loaded = CaptureFile::load(&out).unwrap();
    assert!(loaded.redacted);
    assert!(loaded.entries.iter().any(|e| e.url.contains("/api/items")));
}
