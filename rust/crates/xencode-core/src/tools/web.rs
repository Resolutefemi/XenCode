//! `web_fetch` — public-HTTP text fetch with an SSRF guard.
//!
//! Only absolute http(s) URLs to public hosts: loopback, unspecified,
//! link-local, site-local (RFC1918), multicast and CGNAT/metadata ranges are
//! blocked before connecting. Redirects are re-validated hop by hop (max 3).
//! HTML is reduced to readable text.


const USER_AGENT: &str = "XenCode/2.0 (+https://xencode.dev)";
const TIMEOUT_SECS: u64 = 15;
const MAX_REDIRECTS: usize = 3;

/// SSRF guard for a URL string.
/// Returns `None` when allowed, otherwise the reason (also used by tests).
pub fn blocked_reason(raw_url: &str) -> Option<String> {
    let url = match reqwest::Url::parse(raw_url.trim()) {
        Ok(u) => u,
        Err(_) => return Some("not a valid URL".into()),
    };
    if url.scheme() != "http" && url.scheme() != "https" {
        return Some("only http(s) URLs are allowed".into());
    }
    // reqwest::Url keeps IPv6 brackets in host_str(); strip them so literal
    // IPs parse without a DNS round-trip.
    let host = url
        .host_str()?
        .trim_start_matches('[')
        .trim_end_matches(']')
        .to_ascii_lowercase();
    if host == "localhost" || host.ends_with(".local") || host.ends_with(".internal") {
        return Some(format!("private host `{host}`"));
    }
    // Literal IPs first (no DNS needed).
    if let Some(ip) = host.parse::<std::net::IpAddr>().ok() {
        return ip_blocked(&ip, &host);
    }
    // Resolve every address; all of them must be public.
    let addrs: Vec<std::net::IpAddr> = match std::net::ToSocketAddrs::to_socket_addrs(&(host.as_str(), 80)) {
        Ok(a) => a.map(|s| s.ip()).collect(),
        Err(_) => return Some(format!("cannot resolve host `{host}`")),
    };
    if addrs.is_empty() {
        return Some(format!("cannot resolve host `{host}`"));
    }
    for ip in addrs {
        if let Some(reason) = ip_blocked(&ip, &host) {
            return Some(reason);
        }
    }
    None
}

fn ip_blocked(ip: &std::net::IpAddr, host: &str) -> Option<String> {
    let blocked = match ip {
        std::net::IpAddr::V4(v4) => {
            let o = v4.octets();
            v4.is_loopback()
                || v4.is_unspecified()
                || v4.is_link_local() // 169.254/16 (incl. cloud metadata)
                || v4.is_private() // 10/8, 172.16/12, 192.168/16
                || v4.is_broadcast()
                || v4.is_multicast()
                || (o[0] == 100 && o[1] >= 64 && o[1] <= 127) // 100.64/10 CGNAT
                || o[0] == 0
        }
        std::net::IpAddr::V6(v6) => v6.is_loopback() || v6.is_unspecified() || v6.is_multicast(),
    };
    if blocked {
        Some(format!("blocked address `{host}` ({ip})"))
    } else {
        None
    }
}

/// Blocking fetch used by the tool layer. HTML content types are reduced to
/// readable text; the result is clipped at `max_chars`.
pub async fn fetch_text(raw_url: &str, max_chars: usize) -> Result<String, String> {
    if let Some(reason) = blocked_reason(raw_url) {
        return Err(format!("blocked url: {reason}"));
    }
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(TIMEOUT_SECS))
        .redirect(reqwest::redirect::Policy::none()) // redirects re-validated manually
        .build()
        .map_err(|e| e.to_string())?;

    let mut current = raw_url.trim().to_string();
    for _ in 0..=MAX_REDIRECTS {
        let resp = client
            .get(&current)
            .header(reqwest::header::USER_AGENT, USER_AGENT)
            .header(
                reqwest::header::ACCEPT,
                "text/html,application/json,text/plain;q=0.9,*/*;q=0.5",
            )
            .send()
            .await
            .map_err(|e| format!("{e}"))?;

        // Follow only redirects to public hosts.
        if resp.status().is_redirection() {
            let loc = resp
                .headers()
                .get(reqwest::header::LOCATION)
                .and_then(|v| v.to_str().ok())
                .map(|s| s.to_string());
            if let Some(loc) = loc {
                let next = resolve_redirect(&current, &loc)?;
                if let Some(reason) = blocked_reason(&next) {
                    return Err(format!("redirect blocked: {reason}"));
                }
                current = next;
                continue;
            }
            return Err(format!("HTTP {} for {current}", resp.status()));
        }
        let status = resp.status();
        if !status.is_success() {
            return Err(format!("HTTP {status} for {current}"));
        }
        let ctype = resp
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_ascii_lowercase();
        let body = resp.text().await.map_err(|e| e.to_string())?;
        let text = if ctype.contains("html") || (ctype.is_empty() && body.trim_start().starts_with('<')) {
            html_to_text(&body)
        } else {
            body
        };
        return Ok(clip(text.trim(), max_chars));
    }
    Err("too many redirects".into())
}

fn resolve_redirect(current: &str, location: &str) -> Result<String, String> {
    reqwest::Url::parse(current)
        .and_then(|base| base.join(location))
        .map(|u| u.to_string())
        .map_err(|e| format!("bad redirect location: {e}"))
}

fn clip(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        s.to_string()
    } else {
        let cut: String = s.chars().take(max).collect();
        format!("{cut}\n...(truncated at {max} chars)")
    }
}

/// HTML -> readable text: drops script/style/head blocks, converts block-ish
/// closings to newlines, strips tags, decodes the common entities.
pub fn html_to_text(html: &str) -> String {
    let mut s = html.to_string();
    for tag in ["script", "style", "noscript", "svg", "head"] {
        s = drop_block(&s, tag);
    }
    s = regex::Regex::new(r"(?i)<br\s*/?>").map(|r| r.replace_all(&s, "\n").to_string()).unwrap_or(s);
    s = regex::Regex::new(r"(?i)</(p|div|section|article|h[1-6]|li|tr|table|blockquote|pre)>")
        .map(|r| r.replace_all(&s, "\n").to_string())
        .unwrap_or(s);
    s = regex::Regex::new(r"(?s)<[^>]+>").map(|r| r.replace_all(&s, " ").to_string()).unwrap_or(s);
    for (entity, ch) in [
        ("&amp;", "&"),
        ("&lt;", "<"),
        ("&gt;", ">"),
        ("&quot;", "\""),
        ("&#39;", "'"),
        ("&apos;", "'"),
        ("&nbsp;", " "),
        ("&mdash;", "\u{2014}"),
        ("&ndash;", "\u{2013}"),
    ] {
        s = s.replace(entity, ch);
    }
    s = regex::Regex::new(r"[ \t\r]+").map(|r| r.replace_all(&s, " ").to_string()).unwrap_or(s);
    s = regex::Regex::new(r"\n ").map(|r| r.replace_all(&s, "\n").to_string()).unwrap_or(s);
    s = regex::Regex::new(r"\n{3,}").map(|r| r.replace_all(&s, "\n\n").to_string()).unwrap_or(s);
    s.trim().to_string()
}

fn drop_block(s: &str, tag: &str) -> String {
    // (?is) makes the pattern case-insensitive and dot-all; regex operates on
    // &str directly so no ASCII-lowering index drift is possible.
    match regex::Regex::new(&format!(r"(?is)<{tag}[^>]*>.*?</{tag}>")) {
        Ok(re) => re.replace_all(s, " ").to_string(),
        Err(_) => s.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn guard_blocks_private_and_non_http_targets() {
        assert!(blocked_reason("file:///etc/passwd").is_some());
        assert!(blocked_reason("ftp://example.com/x").is_some());
        assert!(blocked_reason("http://127.0.0.1:8080/x").is_some());
        assert!(blocked_reason("http://10.1.2.3/x").is_some());
        assert!(blocked_reason("http://192.168.1.4/x").is_some());
        assert!(blocked_reason("http://172.16.0.9/x").is_some());
        assert!(blocked_reason("http://169.254.169.254/latest/meta-data").is_some());
        assert!(blocked_reason("http://100.100.100.100/x").is_some());
        assert!(blocked_reason("http://localhost/x").is_some());
        assert!(blocked_reason("http://db.internal/x").is_some());
        assert!(blocked_reason("not a url").is_some());
    }

    #[test]
    fn guard_allows_public_hosts() {
        // DNS-resolvable public host (sandbox may lack DNS: accept resolve errors as "allowed reason only for resolution").
        match blocked_reason("https://example.com/") {
            None => {}                                  // resolved and public
            Some(r) => assert!(r.contains("cannot resolve"), "unexpected: {r}"),
        }
        // Literal public IPs must always pass.
        assert_eq!(None, blocked_reason("http://93.184.216.34/x"));
        assert_eq!(None, blocked_reason("http://[2606:2800:220:1:248:1893:25c8:1946]/x"));
    }

    #[test]
    fn html_to_text_strips_scripts_and_tags() {
        let html = "<html><head><style>.x{}</style></head><body><script>evil()</script>" //
            .to_owned()
            + "<h1>Welcome</h1><p>First&nbsp;paragraph &amp; more</p><p>Second</p></body></html>";
        let text = html_to_text(&html);
        assert!(text.contains("Welcome"), "{text}");
        assert!(text.contains("First paragraph & more"), "{text}");
        assert!(text.contains("Second"), "{text}");
        assert!(!text.contains('<'), "{text}");
        assert!(!text.contains("evil()"), "{text}");
    }

}
