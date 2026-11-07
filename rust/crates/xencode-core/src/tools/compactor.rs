//! Context compaction: when history grows past the threshold, older turns are
//! folded into a digest message (twin of the Kotlin Compactor).

use crate::chat::{ChatMessage, Role};

pub const COMPACT_THRESHOLD: usize = 40;
pub const KEEP_HEAD: usize = 2;
pub const KEEP_TAIL: usize = 10;

pub fn should_compact(messages: &[ChatMessage]) -> bool {
    messages.len() > COMPACT_THRESHOLD
}

/// Returns a compacted copy, or `None` when under the threshold.
pub fn compact(messages: &[ChatMessage]) -> Option<Vec<ChatMessage>> {
    if !should_compact(messages) {
        return None;
    }
    let mut out: Vec<ChatMessage> = messages[..KEEP_HEAD.min(messages.len())].to_vec();
    let mid_start = KEEP_HEAD.min(messages.len());
    let mid_end = messages.len().saturating_sub(KEEP_TAIL);
    let mut digest = String::from("[context compacted] Summary of earlier turns:\n");
    for m in &messages[mid_start..mid_end] {
        match m.role {
            Role::User => {
                if let Some(c) = &m.content {
                    digest.push_str(&format!("- user asked: {}\n", first_chars(c, 120)));
                }
            }
            Role::Assistant => {
                if let Some(c) = &m.content {
                    if !c.is_empty() {
                        digest.push_str(&format!("- agent said: {}\n", first_chars(c, 120)));
                    }
                }
                for tc in &m.tool_calls {
                    digest.push_str(&format!("- agent called tool {} ({})\n", tc.name, first_chars(&tc.args_json, 60)));
                }
            }
            Role::Tool => {
                if let Some(c) = &m.content {
                    digest.push_str(&format!("- tool {} returned: {}\n", m.name.as_deref().unwrap_or("?"), first_chars(c, 80)));
                }
            }
            Role::System => {}
        }
    }
    digest.push_str(&format!("\n({} turns folded; newer turns follow verbatim)", mid_end - mid_start));
    out.push(ChatMessage::user(digest));
    out.extend_from_slice(&messages[mid_end..]);
    Some(out)
}

fn first_chars(s: &str, n: usize) -> String {
    if s.chars().count() <= n {
        s.replace('\n', " ")
    } else {
        let cut: String = s.chars().take(n).collect();
        format!("{cut}…")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::chat::ToolCallReq;

    #[test]
    fn compaction_folds_middle_keeps_head_tail() {
        let mut msgs = vec![ChatMessage::user("first"), ChatMessage::assistant("second")];
        for i in 0..50 {
            msgs.push(ChatMessage::user(format!("q{i}")));
            msgs.push(ChatMessage::assistant(format!("a{i}")));
        }
        let out = compact(&msgs).unwrap();
        assert!(out.len() < msgs.len());
        assert!(out.iter().any(|m| m.content.as_deref().unwrap_or("").contains("[context compacted]")));
        assert_eq!(out[0].content.as_deref(), Some("first"));
        // newest assistant message survives verbatim
        let last = out.last().unwrap();
        assert_eq!(last.content.as_deref(), Some("a49"));
    }

    #[test]
    fn no_compaction_below_threshold() {
        let msgs = vec![ChatMessage::user("hi")];
        assert!(compact(&msgs).is_none());
        assert!(!should_compact(&msgs));
    }

    #[test]
    fn tool_calls_recorded_in_digest() {
        let mut msgs = vec![ChatMessage::user("kickoff"), ChatMessage::assistant("on it")];
        for _ in 0..45 {
            msgs.push(ChatMessage {
                role: Role::Assistant,
                content: Some("working".into()),
                tool_calls: vec![ToolCallReq { id: "1".into(), name: "grep".into(), args_json: "{}".into() }],
                tool_call_id: None,
                name: None,
            images: vec![],
            });
        }
        let out = compact(&msgs).unwrap();
        let digest = out
            .iter()
            .find(|m| m.content.as_deref().unwrap_or("").contains("[context compacted]"))
            .map(|m| m.content.as_deref().unwrap().to_string())
            .expect("digest message present");
        assert!(digest.contains("agent called tool grep"), "{digest}");
    }
}
