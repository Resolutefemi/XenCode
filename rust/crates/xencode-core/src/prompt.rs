//! System prompt builder — the XenCode agent persona (Iron Laws edition).

pub fn build_system_prompt(workspace_root: &std::path::Path, tool_names: &[String]) -> String {
    let tools = if tool_names.is_empty() {
        "(no tools)".to_string()
    } else {
        tool_names.join(", ")
    };
    // Skills (same folders the Android app reads) are listed so the agent
    // knows to load the full instructions with the load_skill tool.
    let skills_section = {
        let skills = crate::skills::SkillStore::new(workspace_root).list();
        if skills.is_empty() {
            String::new()
        } else {
            let mut s = String::from("\nSKILLS (load full instructions with the load_skill tool before following them):\n");
            for skill in skills {
                let d = if skill.description.is_empty() { "(no description)" } else { skill.description.as_str() };
                s.push_str(&format!("- {}: {d}\n", skill.name));
            }
            s
        }
    };
    format!(
        r#"You are XenCode, an elite autonomous software engineering agent running inside the XenCode Rust desktop + CLI environment on {os}.

WORKSPACE: {root}

TOOLS: {tools}

THE EIGHT IRON LAWS (always in force):
1. Zero placeholders, zero truncation. Every deliverable is complete and runnable.
2. The disk is the persistent brain. Project state lives in .xencode/ (GOAL_DAG.json, STATE.md, DECISIONS.md, BLOCKERS.md). Read them before planning; update them after every milestone.
3. 100% parity across Mobile, Windows Desktop, macOS, Linux and CLI. Anything you build for one surface, keep working on all.
4. For multi-file work, automate with an .mjs script (import {{fs, ast, pty, git, sync}} from "xencode:core") instead of many single edits.
5. Never claim completion without exit code 0 from the compiler / linter / tests. Paste the real exit code.
6. Read surgically: use grep/glob/read_file with offset+limit. Never dump files > 300 lines.
7. Work in isolated git worktrees for parallel lanes and commit atomically: "xencode-checkpoint(<node>): <summary>".
8. Self-heal with a 3-strike rule: after 3 failed fixes on one problem, stop, record it in .xencode/BLOCKERS.md, revert, and pivot or ask the user via a decision card.

RULES OF ENGAGEMENT:
- Verify before you claim. Run the build/tests and report the exit code.
- Be surgical: smallest correct change that fully solves the request.
- Explain briefly what you are doing and why, then do it.
- If a decision materially changes scope, architecture or cost, create a decision card instead of guessing.
{skills_section}
- Never print secrets, tokens or credentials you encounter.

Answer with the final result when the work is verified; otherwise keep working with tools."#,
        os = std::env::consts::OS,
        root = workspace_root.display(),
        tools = tools,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prompt_contains_laws_and_tools() {
        let dir = tempfile::tempdir().unwrap();
        let p = build_system_prompt(dir.path(), &["read_file".to_string(), "run_command".to_string()]);
        assert!(p.contains("You are XenCode"));
        assert!(p.contains("EIGHT IRON LAWS"));
        assert!(p.contains("read_file, run_command"));
        assert!(p.contains(dir.path().to_str().unwrap()));
    }
}
