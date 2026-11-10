//! CDP connection: JSON-RPC-style DevTools protocol over a byte stream.
//!
//! Chromium's `--remote-debugging-pipe` frames messages as NUL-delimited
//! JSON. This module is transport-agnostic (`tokio` AsyncRead/AsyncWrite
//! halves) so tests drive it through an in-memory duplex instead of a real
//! browser process.
//!
//! Envelopes (flatten mode):
//! - call:     `{ "id": N, "method": "...", "params": {...}, "sessionId": "..."? }`
//! - response: `{ "id": N, "result": ... }` / `{ "id": N, "error": {...} }`
//! - event:    `{ "method": "...", "params": {...}, "sessionId": "..."? }`
//!
//! The reader task shares the pending-call map: a process crash closes every
//! outstanding call with [`CdpError::Closed`] instead of hanging.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;
use std::time::Duration;

use serde_json::Value;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::{broadcast, oneshot, Mutex};

#[derive(Debug, thiserror::Error)]
pub enum CdpError {
    #[error("browser closed the debug pipe: {0}")]
    Closed(String),
    #[error("cdp call `{method}` timed out after {secs}s")]
    Timeout { method: String, secs: u64 },
    #[error("cdp call `{method}` failed: {message}")]
    Remote { method: String, message: String },
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("{0}")]
    Other(String),
}

pub type Result<T> = std::result::Result<T, CdpError>;

/// One protocol event, routed with its target session id (if any).
#[derive(Debug, Clone)]
pub struct Event {
    pub session_id: Option<String>,
    pub method: String,
    pub params: Value,
}

type PendingMap = Arc<Mutex<HashMap<u32, oneshot::Sender<Result<Value>>>>>;

/// Event channel capacity — a lagging consumer (e.g. nobody draining
/// screencast frames) simply misses old events, which is fine: frames are
/// superseded by newer ones anyway.
const EVENT_CAPACITY: usize = 1024;

/// A live CDP connection. Cloneable; the writer is shared behind a mutex.
#[derive(Clone)]
pub struct Connection {
    next_id: Arc<AtomicU32>,
    writer: Arc<Mutex<dyn AsyncWrite + Unpin + Send>>,
    pending: PendingMap,
    events_tx: broadcast::Sender<Event>,
}

impl Connection {
    /// Wrap an established byte stream (both halves) into a connection.
    /// Returns the connection plus a broadcast handle to its event stream
    /// (navigation waiters and the screencast pump subscribe separately).
    pub fn start<R, W>(read: R, write: W) -> (Connection, broadcast::Receiver<Event>)
    where
        R: AsyncRead + Unpin + Send + 'static,
        W: AsyncWrite + Unpin + Send + 'static,
    {
        let (events_tx, events_rx) = broadcast::channel(EVENT_CAPACITY);
        let writer: Arc<Mutex<dyn AsyncWrite + Unpin + Send>> =
            Arc::new(Mutex::new(Box::new(write) as Box<dyn AsyncWrite + Unpin + Send>));
        let pending: PendingMap = Arc::new(Mutex::new(HashMap::new()));

        // Reader task: split frames on NUL, dispatch by shape.
        let reader_pending = pending.clone();
        let reader_tx = events_tx.clone();
        tokio::spawn(async move {
            let mut buf: Vec<u8> = Vec::with_capacity(64 * 1024);
            let mut chunk = [0u8; 64 * 1024];
            let mut rx = read;
            'outer: loop {
                // Drain complete frames already buffered.
                while let Some(pos) = buf.iter().position(|&b| b == 0) {
                    let frame: Vec<u8> = buf.drain(..=pos).collect();
                    let frame = &frame[..frame.len() - 1];
                    if frame.is_empty() {
                        continue;
                    }
                    if let Ok(v) = serde_json::from_slice::<Value>(frame) {
                        dispatch(v, &reader_pending, &reader_tx).await;
                    }
                }
                loop {
                    match rx.read(&mut chunk).await {
                        Ok(0) => break 'outer, // EOF — browser gone
                        Ok(n) => {
                            buf.extend_from_slice(&chunk[..n]);
                            break; // back to frame draining
                        }
                        Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                        Err(_) => break 'outer,
                    }
                }
            }
            // EOF: fail every pending call.
            let waiters: Vec<_> = reader_pending.lock().await.drain().collect();
            for (_, tx) in waiters {
                let _ = tx.send(Err(CdpError::Closed("debug pipe reached EOF".into())));
            }
        });

        (
            Connection {
                next_id: Arc::new(AtomicU32::new(1)),
                writer,
                pending,
                events_tx,
            },
            events_rx,
        )
    }

    /// Send one command and await its response.
    pub async fn call(
        &self,
        method: &str,
        params: Value,
        session: Option<&str>,
        timeout: Duration,
    ) -> Result<Value> {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let mut msg = serde_json::json!({ "id": id, "method": method, "params": params });
        if let Some(s) = session {
            msg["sessionId"] = Value::String(s.to_string());
        }

        let (tx, rx) = oneshot::channel();
        self.pending.lock().await.insert(id, tx);

        let mut frame = serde_json::to_vec(&msg).map_err(|e| CdpError::Other(e.to_string()))?;
        frame.push(0);
        {
            let mut w = self.writer.lock().await;
            w.write_all(&frame).await?;
            w.flush().await?;
        }

        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(res)) => res.map_err(|e| match e {
                CdpError::Remote { message, .. } => CdpError::Remote {
                    method: method.to_string(),
                    message,
                },
                other => other,
            }),
            Ok(Err(_)) => Err(CdpError::Closed("connection dropped the call".into())),
            Err(_) => Err(CdpError::Timeout {
                method: method.to_string(),
                secs: timeout.as_secs(),
            }),
        }
    }

    /// Publish an event manually (used by tests and by Space-level pumps).
    pub fn emit(&self, ev: Event) {
        let _ = self.events_tx.send(ev);
    }
}

async fn dispatch(v: Value, pending: &PendingMap, events_tx: &broadcast::Sender<Event>) {
    // A response carries `id`; an event carries `method` without one.
    if let Some(id) = v.get("id").and_then(Value::as_u64) {
        let res = if let Some(err) = v.get("error") {
            let msg = err
                .get("message")
                .and_then(Value::as_str)
                .unwrap_or("unknown error")
                .to_string();
            Err(CdpError::Remote { method: String::new(), message: msg })
        } else {
            Ok(v.get("result").cloned().unwrap_or(Value::Null))
        };
        if let Some(tx) = pending.lock().await.remove(&(id as u32)) {
            let _ = tx.send(res);
        }
        return;
    }
    if let Some(method) = v.get("method").and_then(Value::as_str) {
        let ev = Event {
            session_id: v
                .get("sessionId")
                .and_then(Value::as_str)
                .map(str::to_string),
            method: method.to_string(),
            params: v.get("params").cloned().unwrap_or(Value::Null),
        };
        let _ = events_tx.send(ev);
    }
}


#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncWriteExt, DuplexStream};

    fn peer_json(id: u64, result: Value) -> Vec<u8> {
        let mut v = serde_json::json!({ "id": id, "result": result })
            .to_string()
            .into_bytes();
        v.push(0);
        v
    }

    /// Wire a connection to a fake browser peer. Returns the connection,
    /// the event stream, and the peer's request-read / response-write ends.
    fn crossbar() -> (
        Connection,
        tokio::sync::broadcast::Receiver<Event>,
        DuplexStream,
        DuplexStream,
    ) {
        let (conn_read, fake_write) = tokio::io::duplex(64 * 1024);
        let (fake_read, conn_write) = tokio::io::duplex(64 * 1024);
        let (conn, events) = Connection::start(conn_read, conn_write);
        (conn, events, fake_read, fake_write)
    }

    /// Fake peer task: echoes every request id with a fixed result payload.
    fn echo_peer(mut read: DuplexStream, mut write: DuplexStream, result: Value) {
        tokio::spawn(async move {
            let mut buf = vec![0u8; 16 * 1024];
            loop {
                match read.read(&mut buf).await {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        let text = String::from_utf8_lossy(&buf[..n]);
                        for frame in text.split('\0') {
                            if frame.trim().is_empty() {
                                continue;
                            }
                            let Ok(v) = serde_json::from_str::<Value>(frame) else { continue };
                            let Some(id) = v["id"].as_u64() else { continue };
                            let mut reply = serde_json::json!({"id": id, "result": result})
                                .to_string()
                                .into_bytes();
                            reply.push(0);
                            if write.write_all(&reply).await.is_err() {
                                break;
                            }
                        }
                    }
                }
            }
        });
    }

    #[tokio::test]
    async fn call_matches_response_by_id() {
        let (conn, _events, fake_read, fake_write) = crossbar();
        echo_peer(fake_read, fake_write, serde_json::json!({"ok": true}));
        let res = conn
            .call(
                "Target.getTargets",
                serde_json::json!({}),
                None,
                Duration::from_secs(2),
            )
            .await
            .unwrap();
        assert_eq!(res["ok"], true);
    }

    #[tokio::test]
    async fn timeout_when_peer_never_answers() {
        let (conn_read, _fw) = tokio::io::duplex(64 * 1024);
        let (_fr, conn_write) = tokio::io::duplex(64 * 1024);
        let (conn, _events) = Connection::start(conn_read, conn_write);
        let err = conn
            .call("Page.navigate", serde_json::json!({}), None, Duration::from_millis(80))
            .await
            .unwrap_err();
        assert!(matches!(err, CdpError::Timeout { .. }), "got {err:?}");
    }

    #[tokio::test]
    async fn eof_fails_pending_calls() {
        let (conn_read, fake_write) = tokio::io::duplex(64 * 1024);
        let (_fake_read, conn_write) = tokio::io::duplex(64 * 1024);
        let (conn, _events) = Connection::start(conn_read, conn_write);
        drop(fake_write); // peer hangs up while a call is in flight
        let err = conn
            .call("Target.createTarget", serde_json::json!({}), None, Duration::from_secs(2))
            .await
            .unwrap_err();
        assert!(matches!(err, CdpError::Closed(_)), "got {err:?}");
    }

    #[tokio::test]
    async fn events_route_with_session_ids() {
        let (conn_read, mut fake_write) = tokio::io::duplex(64 * 1024);
        let (_fake_read, conn_write) = tokio::io::duplex(64 * 1024);
        let (conn, mut events) = Connection::start(conn_read, conn_write);
        // Simulate Chromium pushing two events through the pipe.
        let mut m1 = serde_json::json!({
            "method": "Page.loadEventFired",
            "params": {"timestamp": 1.0}
        })
        .to_string()
        .into_bytes();
        m1.push(0);
        let mut m2 = serde_json::json!({
            "sessionId": "SESS-1",
            "method": "Page.screencastFrame",
            "params": {"data": "abc"}
        })
        .to_string()
        .into_bytes();
        m2.push(0);
        let mut all = m1;
        all.extend_from_slice(&m2);
        fake_write.write_all(&all).await.unwrap();

        let first = events.recv().await.unwrap();
        assert_eq!(first.method, "Page.loadEventFired");
        assert!(first.session_id.is_none());
        let second = events.recv().await.unwrap();
        assert_eq!(second.method, "Page.screencastFrame");
        assert_eq!(second.session_id.as_deref(), Some("SESS-1"));
        drop(conn);
    }

    #[tokio::test]
    async fn remote_errors_surface_the_message() {
        let (conn, _events, mut fake_read, mut fake_write) = crossbar();
        tokio::spawn(async move {
            let mut buf = vec![0u8; 16 * 1024];
            loop {
                match fake_read.read(&mut buf).await {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        let text = String::from_utf8_lossy(&buf[..n]);
                        for frame in text.split('\0') {
                            if frame.trim().is_empty() {
                                continue;
                            }
                            let Ok(v) = serde_json::from_str::<Value>(frame) else { continue };
                            let Some(id) = v["id"].as_u64() else { continue };
                            let mut reply = serde_json::json!({
                                "id": id,
                                "error": {"code": -32601, "message": "net::ERR_NAME_NOT_RESOLVED"}
                            })
                            .to_string()
                            .into_bytes();
                            reply.push(0);
                            let _ = fake_write.write_all(&reply).await;
                        }
                    }
                }
            }
        });
        let err = conn
            .call("Page.navigate", serde_json::json!({}), None, Duration::from_secs(2))
            .await
            .unwrap_err();
        assert!(
            err.to_string().contains("ERR_NAME_NOT_RESOLVED"),
            "got {err:?}"
        );
    }

    #[tokio::test]
    async fn session_scoped_calls_carry_the_session_field() {
        let (conn, _events, fake_read, fake_write) = crossbar();
        // The fake peer echoes the request's sessionId back in the result so
        // the test can assert it crossed the wire.
        tokio::spawn(async move {
            let mut read = fake_read;
            let mut write = fake_write;
            let mut buf = vec![0u8; 16 * 1024];
            loop {
                match read.read(&mut buf).await {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        let text = String::from_utf8_lossy(&buf[..n]);
                        for frame in text.split('\0') {
                            if frame.trim().is_empty() {
                                continue;
                            }
                            let Ok(v) = serde_json::from_str::<Value>(frame) else { continue };
                            let Some(id) = v["id"].as_u64() else { continue };
                            let sess = v["sessionId"].as_str().unwrap_or("MISSING").to_string();
                            let mut reply = serde_json::json!({
                                "id": id,
                                "result": {"session": sess}
                            })
                            .to_string()
                            .into_bytes();
                            reply.push(0);
                            let _ = write.write_all(&reply).await;
                        }
                    }
                }
            }
        });
        let res = conn
            .call(
                "Page.enable",
                serde_json::json!({}),
                Some("SESS-42"),
                Duration::from_secs(2),
            )
            .await
            .unwrap();
        assert_eq!(res["session"], "SESS-42");
    }
}
