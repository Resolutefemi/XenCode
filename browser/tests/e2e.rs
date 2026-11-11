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

// ---------------------------------------------------------------------
// Phase 6.3 — recorder
// ---------------------------------------------------------------------

/// A recording fixture with real inputs: a text field, a toggle button and
/// a password-style field. The recorder must turn agent-driven interactions
/// (real input events, same as a human's) into replayable steps.
fn record_fixture(dir: &std::path::Path) -> std::path::PathBuf {
    let page = dir.join("record.html");
    std::fs::write(
        &page,
        r#"<!doctype html>
<html>
<head><title>XenRecord</title></head>
<body>
  <form id="f" onsubmit="return false;">
    <input id="who" type="text" value=""/>
    <input id="pw" type="password" value=""/>
    <button id="save" onclick="document.getElementById('status').textContent = 'saved ' + document.getElementById('who').value;">Save</button>
    <p id="status">idle</p>
  </form>
</body>
</html>"#,
    )
    .unwrap();
    page
}

#[tokio::test]
async fn e2e_recorder_captures_interactions_and_emits_script() {
    use xencode_browser::{
        Recorder, RecordOptions, RecStep, Selector, Space, SpaceKind, SpaceOptions,
        to_quickjs_script, RecordMeta,
    };

    let _browser_slot = BROWSER_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    if have_browser().is_none() {
        eprintln!("skipping: no browser found");
        return;
    }

    let dir = tempfile::tempdir().unwrap();
    let page = record_fixture(dir.path());
    let url = format!("file://{}", page.display());

    let space = Space::launch(SpaceOptions {
        kind: SpaceKind::CleanRoom,
        headless: true,
        ..Default::default()
    })
    .await
    .expect("launch space");

    let recorder = Recorder::start(&space, RecordOptions::default())
        .await
        .expect("install recorder");

    // Drive real interactions (same pipeline a human's input produces).
    space.navigate(&url).await.expect("navigate");
    space
        .fill(&Selector::Css("#who".into()), "Femi")
        .await
        .expect("fill name");
    space
        .fill(&Selector::Css("#pw".into()), "hunter2")
        .await
        .expect("fill password");
    space.click(&Selector::Css("#save".into())).await.expect("click save");
    let status = space
        .evaluate("document.getElementById('status').textContent")
        .await
        .unwrap();
    assert_eq!(status, "saved Femi", "the recorded session must be real");

    let (steps, truncated) = recorder.stop().await.expect("stop recorder");
    assert!(!truncated);
    assert!(
        steps.iter().any(|s| matches!(s, RecStep::Fill { selector, value, redacted: false }
            if selector.contains("#who") && value == "Femi")),
        "text fill recorded with value: {steps:?}"
    );
    assert!(
        steps.iter().any(|s| matches!(s, RecStep::Fill { selector, redacted: true, .. }
            if selector.contains("#pw"))),
        "password fill is redacted: {steps:?}"
    );
    assert!(
        steps.iter().any(|s| matches!(s, RecStep::Click { selector, .. }
            if selector.contains("#save"))),
        "save click recorded: {steps:?}"
    );

    // The emitted script replays the same surface + carries the note.
    let meta = RecordMeta {
        start_url: url.clone(),
        recorded_at: "e2e".into(),
        redacted_count: 1,
    };
    let script = to_quickjs_script(&steps, &meta);
    assert!(script.contains("xen.browser.fill("), "{script}");
    assert!(script.contains("xen.browser.click("), "{script}");
    assert!(script.contains("password"), "{script}");
    drop(space);
}

// ---------------------------------------------------------------------
// Phase 6.4 — scrape + exports + fleet
// ---------------------------------------------------------------------

/// A two-page catalog fixture: `page1.html` lists two products and links to
/// `page2.html` with one more.
fn scrape_fixture(dir: &std::path::Path) -> (String, String) {
    let item = |name: &str, price: &str| {
        format!(
            r#"<li class="product"><h3 class="name">{name}</h3><span class="price">{price}</span><a class="go" href="/p/{name}">view</a></li>"#
        )
    };
    let p1 = dir.join("page1.html");
    std::fs::write(
        &p1,
        format!(
            r#"<!doctype html><html><head><title>Catalog 1</title></head><body>
            <h1 id="heading">Catalog page one</h1>
            <ul id="list">{}</li>{}</li>
            <a id="next" href="page2.html">Next</a>
            </body></html>"#,
            item("Pen", "$2"),
            item("Pad", "$9")
        ),
    )
    .unwrap();
    let p2 = dir.join("page2.html");
    std::fs::write(
        &p2,
        format!(
            r#"<!doctype html><html><head><title>Catalog 2</title></head><body>
            <h1 id="heading">Catalog page two</h1>
            <ul id="list">{}</li>
            </body></html>"#,
            item("Ink", "$5")
        ),
    )
    .unwrap();
    (
        format!("file://{}", p1.display()),
        format!("file://{}", p2.display()),
    )
}

#[tokio::test]
async fn e2e_scrape_rows_pagination_and_exports() {
    use xencode_browser::{
        scrape, to_csv, to_markdown, FieldSpec, ScrapeSpec, Space, SpaceKind,
        SpaceOptions,
    };

    let _browser_slot = BROWSER_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    if have_browser().is_none() {
        eprintln!("skipping: no browser found");
        return;
    }

    let dir = tempfile::tempdir().unwrap();
    let (url1, url2) = scrape_fixture(dir.path());
    // rewrite the next link to the absolute file URL (relative works too,
    // but keep the assertion independent of file-url resolution)
    let p1 = dir.path().join("page1.html");
    let html = std::fs::read_to_string(&p1).unwrap().replace("page2.html", &url2);
    std::fs::write(&p1, html).unwrap();

    let spec = ScrapeSpec {
        item: Some("li.product".into()),
        fields: vec![
            FieldSpec::parse("name=h3.name").unwrap(),
            FieldSpec::parse("price=span.price").unwrap(),
            FieldSpec::parse("url=a.go@href").unwrap(),
        ],
        page_fields: vec![FieldSpec::parse("page=h1#heading").unwrap()],
        next: Some("#next".into()),
        max_pages: 2,
        wait_ms: 150,
        ..Default::default()
    };

    let space = Space::launch(SpaceOptions {
        kind: SpaceKind::CleanRoom,
        headless: true,
        ..Default::default()
    })
    .await
    .expect("launch space");
    space.navigate(&url1).await.expect("navigate");

    // single-page extraction first
    let one = xencode_browser::scrape_page(&space, &spec).await.expect("extract page 1");
    assert_eq!(one.rows.len(), 2, "{:?}", one.rows);
    assert_eq!(one.rows[0]["name"], "Pen");
    assert_eq!(one.rows[0]["price"], "$2");
    assert!(one.rows[0]["url"].as_str().unwrap().contains("/p/Pen"));
    assert_eq!(one.rows[0]["page"], "Catalog page one");

    // full pagination run
    let pages = scrape(&space, &spec).await.expect("paginate");
    assert_eq!(pages.len(), 2, "both pages scraped");
    assert!(pages[1].url.ends_with("page2.html"), "{}", pages[1].url);
    let all_rows: Vec<_> = pages.iter().flat_map(|p| p.rows.iter()).collect();
    assert_eq!(all_rows.len(), 3);
    assert_eq!(all_rows[2]["name"], "Ink");

    // exports over the flattened rows
    let rows: Vec<serde_json::Map<String, serde_json::Value>> = pages
        .iter()
        .flat_map(|p| p.rows.clone())
        .collect();
    let csv = to_csv(&rows);
    // serde_json maps iterate alphabetically (no preserve_order) — assert
    // on content, not column order.
    let header = csv.lines().next().unwrap();
    for col in ["name", "page", "price", "url"] {
        assert!(header.contains(col), "header {header:?} must contain {col}");
    }
    assert!(csv.contains("Pen,"), "{csv}");
    assert!(csv.contains("$2"), "{csv}");
    assert!(csv.contains("Ink,"), "{csv}");
    assert!(csv.contains("$5"), "{csv}");
    let md = to_markdown(&rows);
    assert!(md.contains("| name |"), "{md}");
    assert!(md.contains("Pen"), "{md}");
    assert!(md.contains("Ink"), "{md}");
    drop(space);
}

#[tokio::test]
async fn e2e_fleet_scrapes_urls_in_parallel() {
    use xencode_browser::{
        parse_url_list, run_fleet, FieldSpec, FleetOptions, ScrapeSpec, SpaceKind,
    };

    let _browser_slot = BROWSER_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    if have_browser().is_none() {
        eprintln!("skipping: no browser found");
        return;
    }

    let dir = tempfile::tempdir().unwrap();
    let (url1, url2) = scrape_fixture(dir.path());
    let urls = parse_url_list(&format!("{url1}\n{url2}\n"));

    let spec = ScrapeSpec {
        item: Some("li.product".into()),
        fields: vec![FieldSpec::parse("name=h3.name").unwrap()],
        ..Default::default()
    };
    let results = run_fleet(
        urls,
        spec,
        FleetOptions { workers: 2, timeout_ms: 20_000, headless: true, kind: SpaceKind::CleanRoom, ..Default::default() },
    )
    .await
    .expect("fleet run");

    assert_eq!(results.len(), 2, "one result per url, submission order");
    assert!(results[0].ok, "url1: {:?}", results[0].error);
    assert!(results[1].ok, "url2: {:?}", results[1].error);
    assert!(results[0].url.ends_with("page1.html"));
    assert!(results[1].url.ends_with("page2.html"));
    assert_eq!(results[0].rows.len(), 2);
    assert_eq!(results[1].rows.len(), 1);
    assert_eq!(results[0].rows[0]["name"], "Pen");
}
