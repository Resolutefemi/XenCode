//! End-to-end tests against the full router: memory store + mock provider.
//!
//! Covers the whole product surface: magic-link login, API keys, model
//! routing, streaming and non-streaming chat, metering + credits, rate
//! limits, conversation sync, checkpoints, admin prompts and the Stripe
//! webhook signature gate.

use std::sync::Arc;

use serde_json::{json, Value};

fn test_state(rate_limit: u32, signup_credits: i64) -> xencode_cloud::state::AppState {
    let mut cfg = xencode_cloud::config::Config::from_env();
    cfg.dev = true;
    cfg.rate_limit_per_min = rate_limit;
    cfg.signup_credits = signup_credits;
    cfg.mock_llm = true;
    cfg.stripe_webhook_secret = Some("whsec_test".into());
    cfg.admin_token = Some("admintok".into());
    xencode_cloud::state::AppState::new(
        Arc::new(xencode_cloud::store::memory::MemoryStore::new()),
        cfg,
    )
}

async fn spawn_server(rate_limit: u32) -> (String, reqwest::Client) {
    spawn_server_with_credits(rate_limit, 1000).await
}

async fn spawn_server_with_credits(
    rate_limit: u32,
    signup_credits: i64,
) -> (String, reqwest::Client) {
    let state = test_state(rate_limit, signup_credits);
    let app = xencode_cloud::app_router(state);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    let base = format!("http://{addr}");
    let client = reqwest::Client::new();
    (base, client)
}

/// Full login flow: magic link (dev mode) -> session token.
async fn login(base: &str, client: &reqwest::Client, email: &str) -> String {
    let resp = client
        .post(format!("{base}/v1/auth/magic-link"))
        .json(&json!({ "email": email }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let dev_token = resp.json::<Value>().await.unwrap()["dev_token"]
        .as_str()
        .unwrap()
        .to_string();

    let resp = client
        .post(format!("{base}/v1/auth/magic-link/verify"))
        .json(&json!({ "token": dev_token }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    resp.json::<Value>().await.unwrap()["session_token"]
        .as_str()
        .unwrap()
        .to_string()
}

#[tokio::test]
async fn healthz_reports() {
    let (base, client) = spawn_server(60).await;
    let resp = client.get(format!("{base}/healthz")).send().await.unwrap();
    assert_eq!(resp.status(), 200);
    let body: Value = resp.json().await.unwrap();
    assert_eq!(body["status"], "ok");
    assert_eq!(body["db"], "ok");
}

#[tokio::test]
async fn magic_link_flow_issues_session() {
    let (base, client) = spawn_server(60).await;
    let token = login(&base, &client, "dev@xencode.app").await;
    assert!(token.starts_with("xst_"));

    // /v1/me works with the session.
    let resp = client
        .get(format!("{base}/v1/me"))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let body: Value = resp.json().await.unwrap();
    assert_eq!(body["user"]["email"], "dev@xencode.app");
    assert_eq!(body["credits"], 1000); // signup bonus

    // Reusing the magic link fails (single use).
    let resp = client
        .post(format!("{base}/v1/auth/magic-link/verify"))
        .json(&json!({ "token": "xml_reused" }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 401);
}

#[tokio::test]
async fn unauthenticated_requests_are_rejected() {
    let (base, client) = spawn_server(60).await;
    let resp = client.get(format!("{base}/v1/me")).send().await.unwrap();
    assert_eq!(resp.status(), 401);

    let resp = client
        .post(format!("{base}/v1/chat/completions"))
        .json(&json!({ "model": "fast", "messages": [] }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 401);
}

#[tokio::test]
async fn api_keys_work_and_list_never_exposes_them() {
    let (base, client) = spawn_server(60).await;
    let session = login(&base, &client, "keys@xencode.app").await;

    let resp = client
        .post(format!("{base}/v1/auth/api-keys"))
        .bearer_auth(&session)
        .json(&json!({ "name": "desktop" }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let body: Value = resp.json().await.unwrap();
    let key = body["key"].as_str().unwrap().to_string();
    let key_id = body["id"].as_str().unwrap().to_string();
    assert!(key.starts_with("xk_"));

    // The key authenticates.
    let resp = client
        .get(format!("{base}/v1/me"))
        .bearer_auth(&key)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);

    // Listing shows metadata only, never the key.
    let resp = client
        .get(format!("{base}/v1/auth/api-keys"))
        .bearer_auth(&session)
        .send()
        .await
        .unwrap();
    let body: Value = resp.json().await.unwrap();
    assert_eq!(body["keys"][0]["name"], "desktop");
    assert!(body.to_string().len() < 400);

    // Revocation stops authentication.
    let resp = client
        .delete(format!("{base}/v1/auth/api-keys/{key_id}"))
        .bearer_auth(&session)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 204);
    let resp = client
        .get(format!("{base}/v1/me"))
        .bearer_auth(&key)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 401);
}

#[tokio::test]
async fn chat_non_streaming_meters_usage_and_credits() {
    let (base, client) = spawn_server(60).await;
    let token = login(&base, &client, "chat@xencode.app").await;

    let resp = client
        .post(format!("{base}/v1/chat/completions"))
        .bearer_auth(&token)
        .json(&json!({
            "model": "xencode-auto",
            "stream": false,
            "messages": [{ "role": "user", "content": "hello gateway" }]
        }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let body: Value = resp.json().await.unwrap();
    assert!(body["choices"][0]["message"]["content"]
        .as_str()
        .unwrap()
        .contains("mock provider"));
    assert!(body["usage"]["prompt_tokens"].as_i64().unwrap() > 0);
    assert!(body["usage"]["completion_tokens"].as_i64().unwrap() > 0);

    // Usage recorded and credits deducted below the signup bonus.
    let resp = client
        .get(format!("{base}/v1/me"))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    let body: Value = resp.json().await.unwrap();
    let credits = body["credits"].as_i64().unwrap();
    assert!(credits < 1000, "credits should be deducted, got {credits}");
    assert!(!body["usage"].as_array().unwrap().is_empty());
}

#[tokio::test]
async fn chat_streaming_emits_sse_and_meters() {
    let (base, client) = spawn_server(60).await;
    let token = login(&base, &client, "stream@xencode.app").await;

    let resp = client
        .post(format!("{base}/v1/chat/completions"))
        .bearer_auth(&token)
        .json(&json!({
            "model": "fast",
            "stream": true,
            "messages": [{ "role": "user", "content": "stream me something" }]
        }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    assert!(resp
        .headers()
        .get("content-type")
        .unwrap()
        .to_str()
        .unwrap()
        .contains("text/event-stream"));

    let body = resp.text().await.unwrap();
    assert!(body.contains("\"chat.completion.chunk\""));
    assert!(body.trim_end().ends_with("data: [DONE]"));

    // Metering happens asynchronously; give it a moment.
    tokio::time::sleep(std::time::Duration::from_millis(200)).await;
    let resp = client
        .get(format!("{base}/v1/usage"))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    let body: Value = resp.json().await.unwrap();
    assert!(!body["usage"].as_array().unwrap().is_empty());
}

#[tokio::test]
async fn rate_limit_returns_429() {
    let (base, client) = spawn_server(3).await;
    let token = login(&base, &client, "rate@xencode.app").await;

    let mut saw_429 = false;
    for _ in 0..5 {
        let resp = client
            .post(format!("{base}/v1/chat/completions"))
            .bearer_auth(&token)
            .json(&json!({
                "model": "fast",
                "stream": false,
                "messages": [{ "role": "user", "content": "ping" }]
            }))
            .send()
            .await
            .unwrap();
        if resp.status() == 429 {
            saw_429 = true;
            break;
        }
    }
    assert!(saw_429, "expected a 429 after the third request");
}

#[tokio::test]
async fn exhausted_credits_return_402() {
    // One signup credit: the first request burns it, the second is blocked.

    let (base, client) = spawn_server_with_credits(60, 1).await;
    let token = login(&base, &client, "broke@xencode.app").await;

    let first = client
        .post(format!("{base}/v1/chat/completions"))
        .bearer_auth(&token)
        .json(&json!({
            "model": "fast",
            "stream": false,
            "messages": [{ "role": "user", "content": "hello" }]
        }))
        .send()
        .await
        .unwrap();
    assert_eq!(first.status(), 200);

    // Metering for non-streaming requests is synchronous, but give the
    // credit event a beat to land.
    tokio::time::sleep(std::time::Duration::from_millis(100)).await;

    let second = client
        .post(format!("{base}/v1/chat/completions"))
        .bearer_auth(&token)
        .json(&json!({
            "model": "fast",
            "stream": false,
            "messages": [{ "role": "user", "content": "again" }]
        }))
        .send()
        .await
        .unwrap();
    assert_eq!(
        second.status(),
        402,
        "expected payment required after credits exhausted"
    );
}

#[tokio::test]
async fn conversations_sync_and_checkpoint() {
    let (base, client) = spawn_server(60).await;
    let token = login(&base, &client, "sync@xencode.app").await;

    // Create.
    let resp = client
        .post(format!("{base}/v1/conversations"))
        .bearer_auth(&token)
        .json(&json!({ "title": "Fix the login bug", "model": "xencode-auto", "project": "/home/z/work" }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let conv: Value = resp.json().await.unwrap();
    let id = conv["id"].as_str().unwrap().to_string();
    assert_eq!(conv["conversation"]["project"], "/home/z/work");

    // Append messages.
    for (role, content) in [("user", "please fix it"), ("assistant", "done, see diff")] {
        let resp = client
            .post(format!("{base}/v1/conversations/{id}/messages"))
            .bearer_auth(&token)
            .json(&json!({ "role": role, "content": content }))
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), 200);
    }

    // Checkpoint.
    let resp = client
        .post(format!("{base}/v1/conversations/{id}/checkpoints"))
        .bearer_auth(&token)
        .json(&json!({ "label": "before refactor", "snapshot": { "files": ["a.rs"] } }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);

    // List messages + checkpoints.
    let resp = client
        .get(format!("{base}/v1/conversations/{id}/messages"))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    let body: Value = resp.json().await.unwrap();
    assert_eq!(body["messages"].as_array().unwrap().len(), 2);

    let resp = client
        .get(format!("{base}/v1/conversations/{id}/checkpoints"))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    let body: Value = resp.json().await.unwrap();
    assert_eq!(body["checkpoints"].as_array().unwrap().len(), 1);

    // List conversations.
    let resp = client
        .get(format!("{base}/v1/conversations"))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    let body: Value = resp.json().await.unwrap();
    assert_eq!(body["conversations"].as_array().unwrap().len(), 1);

    // Another user cannot read it.
    let other = login(&base, &client, "other@xencode.app").await;
    let resp = client
        .get(format!("{base}/v1/conversations/{id}"))
        .bearer_auth(&other)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 404);
}

#[tokio::test]
async fn admin_prompts_require_token() {
    let (base, client) = spawn_server(60).await;
    let resp = client
        .post(format!("{base}/v1/admin/prompts"))
        .header("x-admin-token", "wrong")
        .json(&json!({ "name": "desktop-default", "content": "You are terse." }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 403);

    let resp = client
        .post(format!("{base}/v1/admin/prompts"))
        .header("x-admin-token", "admintok")
        .json(&json!({ "name": "desktop-default", "content": "You are terse." }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let body: Value = resp.json().await.unwrap();
    assert_eq!(body["prompt"]["version"], 1);

    // Upserting bumps the version.
    let resp = client
        .post(format!("{base}/v1/admin/prompts"))
        .header("x-admin-token", "admintok")
        .json(&json!({ "name": "desktop-default", "content": "You are very terse." }))
        .send()
        .await
        .unwrap();
    let body: Value = resp.json().await.unwrap();
    assert_eq!(body["prompt"]["version"], 2);
}

#[tokio::test]
async fn stripe_webhook_requires_valid_signature() {
    let (base, client) = spawn_server(60).await;

    // Unsigned.
    let resp = client
        .post(format!("{base}/v1/billing/stripe-webhook"))
        .header("stripe-signature", "t=1,v1=bad")
        .body("{}")
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 400);

    // Signed checkout.session.completed adds credits.
    let user_email = "stripe@xencode.app";
    let token = login(&base, &client, user_email).await;
    let me: Value = client
        .get(format!("{base}/v1/me"))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let user_id = me["user"]["id"].as_str().unwrap().to_string();

    use hmac::{Hmac, Mac};
    use sha2::Sha256;
    let payload = json!({
        "type": "checkout.session.completed",
        "data": { "object": {
            "metadata": { "user_id": user_id, "credits": 5000 },
        }}
    })
    .to_string();
    let ts = "1700000000";
    let mut mac = Hmac::<Sha256>::new_from_slice(b"whsec_test").unwrap();
    mac.update(format!("{ts}.{payload}").as_bytes());
    let sig = hex::encode(mac.finalize().into_bytes());

    let resp = client
        .post(format!("{base}/v1/billing/stripe-webhook"))
        .header("stripe-signature", format!("t={ts},v1={sig}"))
        .body(payload)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);

    let me: Value = client
        .get(format!("{base}/v1/me"))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(me["credits"].as_i64().unwrap(), 6000); // 1000 signup + 5000 stripe
}

#[tokio::test]
async fn models_endpoint_lists_aliases() {
    let (base, client) = spawn_server(60).await;
    let token = login(&base, &client, "models@xencode.app").await;
    let resp = client
        .get(format!("{base}/v1/models"))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let body: Value = resp.json().await.unwrap();
    let ids: Vec<&str> = body["data"]
        .as_array()
        .unwrap()
        .iter()
        .map(|m| m["id"].as_str().unwrap())
        .collect();
    assert!(ids.contains(&"xencode-auto"));
    assert!(ids.contains(&"fast"));
    assert!(ids.contains(&"smart"));
}
