//! Context compaction — when the transcript outgrows the budget, older turns
//! collapse into a model-written summary so the loop can keep running.
//!
//! The transcript is split into *atoms* that must move together: an assistant
//! message carrying tool_calls plus every tool result that follows it. Only
//! whole atoms are summarized, so assistant/tool pairing stays valid on the
//! wire after compaction.

use anyhow::Result;

use super::provider::{Message, Provider, Role};

/// Default transcript budget (chars) before compaction kicks in.
pub const DEFAULT_BUDGET_CHARS: usize = 90_000;

/// Rough transcript size — chars of content plus serialized tool arguments.
pub fn estimate_chars(messages: &[Message]) -> usize {
    messages
        .iter()
        .map(|m| {
            let mut n = m.content.len();
            for tc in &m.tool_calls {
                n += tc.name.len() + tc.arguments.to_string().len();
            }
            n
        })
        .sum()
}

/// Index ranges `(start, end)` of atoms: an assistant message with tool_calls
/// groups with the tool results that follow; everything else stands alone.
fn atoms(messages: &[Message]) -> Vec<(usize, usize)> {
    let mut out = Vec::new();
    let mut i = 0;
    while i < messages.len() {
        if messages[i].role == Role::Assistant && !messages[i].tool_calls.is_empty() {
            let start = i;
            let mut end = i;
            let mut j = i + 1;
            while j < messages.len() && messages[j].role == Role::Tool {
                end = j;
                j += 1;
            }
            out.push((start, end));
            i = j;
        } else {
            out.push((i, i));
            i += 1;
        }
    }
    out
}

fn render_turn_text(m: &Message) -> String {
    let mut line = match m.role {
        Role::User => format!("user: {}", m.content),
        Role::Assistant => {
            let mut s = format!("assistant: {}", m.content);
            for tc in &m.tool_calls {
                s.push_str(&format!("\n  calls {}({})", tc.name, tc.arguments));
            }
            s
        }
        Role::Tool => format!("tool result: {}", m.content),
        Role::System => format!("system: {}", m.content),
    };
    if line.chars().count() > 600 {
        let t: String = line.chars().take(600).collect();
        line = format!("{t} …");
    }
    line
}

/// Collapse everything between the system prompt and the recent tail into a
/// summary. Returns the input unchanged when there is nothing worth folding.
pub async fn compact<P: Provider>(
    provider: &P,
    messages: &[Message],
    keep_tail_chars: usize,
) -> Result<Vec<Message>> {
    if messages.len() < 4 {
        return Ok(messages.to_vec());
    }
    let (system, rest) = messages.split_first().expect("non-empty");

    // Group `rest` into atoms and pick the tail worth keeping.
    let groups = atoms(rest);
    let mut keep_from = rest.len();
    let mut kept = 0usize;
    for (start, end) in groups.iter().rev() {
        let size: usize = rest[*start..=*end].iter().map(|m| m.content.len()).sum();
        kept += size;
        keep_from = *start;
        if kept >= keep_tail_chars {
            break;
        }
    }
    if keep_from == 0 {
        return Ok(messages.to_vec()); // nothing older than the tail
    }

    let middle = &rest[..keep_from];
    if middle.is_empty() {
        return Ok(messages.to_vec());
    }

    let mut transcript = String::new();
    for m in middle {
        transcript.push_str(&render_turn_text(m));
        transcript.push('\n');
    }

    let ask = Message::user(format!(
        "These are earlier turns of a coding-agent session. Compress them into a \
         context summary of at most 250 words: the task, every file path touched \
         and what changed, key decisions and results, and the current state. \
         Be specific — the summary replaces this history.\n\n---\n{transcript}"
    ));
    let reply = provider.complete(&[ask], &[], None).await?;
    let summary = reply.content.trim().to_string();

    let mut out = Vec::with_capacity(2 + rest.len() - keep_from);
    out.push(system.clone());
    out.push(Message::user(format!(
        "[Context summary of earlier turns — restored after compaction]\n{summary}"
    )));
    out.extend(rest[keep_from..].iter().cloned());
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agent::provider::{MockProvider, ToolCall};
    use serde_json::{json, Value};

    fn tool_call(id: &str, name: &str, args: Value) -> Message {
        Message {
            role: Role::Assistant,
            content: String::new(),
            tool_calls: vec![ToolCall { id: id.into(), name: name.into(), arguments: args }],
            tool_call_id: None,
        }
    }

    #[test]
    fn atoms_keep_tool_pairs_together() {
        let msgs = vec![
            Message::system("s"),
            Message::user("u"),
            tool_call("t1", "read_file", json!({"path": "a"})),
            Message::tool_result("t1", "…"),
            Message::tool_result("t1", "…"),
            Message::user("u2"),
        ];
        let a = atoms(&msgs);
        assert_eq!(a, vec![(0, 0), (1, 1), (2, 4), (5, 5)]);
    }

    #[tokio::test]
    async fn compaction_folds_old_turns_into_a_summary() {
        let big = "x".repeat(4_000);
        let msgs = vec![
            Message::system("system prompt"),
            Message::user(format!("task with context {big}")),
            tool_call("t1", "read_file", json!({"path": "a.rs"})),
            Message::tool_result("t1", format!("contents {big}")),
            Message::user("follow-up question"),
            Message {
                role: Role::Assistant,
                content: "short answer".into(),
                tool_calls: vec![],
                tool_call_id: None,
            },
        ];
        let before = estimate_chars(&msgs);

        let mock = MockProvider::new(vec![Message {
            role: Role::Assistant,
            content: "The user asked about a.rs; it was read; the task continues."
                .into(),
            tool_calls: vec![],
            tool_call_id: None,
        }]);
        let out = compact(&mock, &msgs, 500).await.unwrap();

        assert_eq!(out[0].role, Role::System, "system stays first");
        assert!(out[1].content.starts_with("[Context summary of earlier turns"));
        assert!(out[1].content.contains("a.rs"), "summary is specific");
        // The tail (user + assistant) survives verbatim.
        assert_eq!(out[out.len() - 2].content, "follow-up question");
        assert_eq!(out[out.len() - 1].content, "short answer");
        assert!(estimate_chars(&out) < before, "compaction must shrink");

        // Pairing stays valid: no dangling assistant tool_calls.
        for (i, m) in out.iter().enumerate() {
            if m.role == Role::Assistant && !m.tool_calls.is_empty() {
                assert!(
                    out[i + 1..].iter().any(|r| r.role == Role::Tool),
                    "assistant tool_calls must keep their results"
                );
            }
        }
    }

    #[tokio::test]
    async fn compaction_skips_short_transcripts() {
        let msgs = vec![Message::system("s"), Message::user("u")];
        let mock = MockProvider::new(vec![]);
        let out = compact(&mock, &msgs, 10).await.unwrap();
        assert_eq!(out.len(), msgs.len(), "nothing to fold");
    }
}
