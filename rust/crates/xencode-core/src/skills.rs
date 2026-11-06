//! Skill discovery for the PC line — the Claude-Code-style skill contract:
//! a skill is a folder with a `SKILL.md` whose optional frontmatter carries
//! `name` and `description`; the body holds the instructions loaded into the
//! agent context on demand.
//!
//! Search order (first hit wins per name):
//!   1. `<workspace>/.xencode/skills/<name>/SKILL.md`  (project skills)
//!   2. `~/.xencode/skills/<name>/SKILL.md`            (global skills)
//!
//! The Android app reads the same folders, so a skill written on the phone is
//! immediately available to the desktop and the CLI.

use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq)]
pub struct Skill {
    pub name: String,
    pub description: String,
    pub path: PathBuf,
    pub body: String,
}

pub struct SkillStore {
    workspace_root: PathBuf,
}

impl SkillStore {
    pub fn new(workspace_root: impl Into<PathBuf>) -> Self {
        Self { workspace_root: workspace_root.into() }
    }

    /// Project skills root, `<workspace>/.xencode/skills`.
    pub fn project_root(&self) -> PathBuf {
        self.workspace_root.join(".xencode").join("skills")
    }

    /// Global skills root, `~/.xencode/skills`.
    pub fn global_root(&self) -> PathBuf {
        let home = std::env::var("USER_HOME_XENCODE")
            .ok()
            .or_else(|| std::env::var("HOME").ok())
            .unwrap_or_else(|| ".".to_string());
        Path::new(&home).join(".xencode").join("skills")
    }

    /// All discoverable skills, project skills first, deduplicated by name.
    pub fn list(&self) -> Vec<Skill> {
        let mut seen = std::collections::BTreeSet::new();
        let mut out = vec![];
        for root in [self.project_root(), self.global_root()] {
            let mut dirs: Vec<PathBuf> = match std::fs::read_dir(&root) {
                Ok(rd) => rd
                    .filter_map(|e| e.ok())
                    .map(|e| e.path())
                    .filter(|p| p.is_dir())
                    .collect(),
                Err(_) => continue,
            };
            dirs.sort();
            for dir in dirs {
                if let Some(skill) = self.parse(&dir) {
                    if seen.insert(skill.name.clone()) {
                        out.push(skill);
                    }
                }
            }
        }
        out.sort_by(|a, b| a.name.cmp(&b.name));
        out
    }

    /// Loads one skill by name (frontmatter stripped); `None` for unknown or
    /// unsafe names.
    pub fn load(&self, name: &str) -> Option<Skill> {
        if !valid_name(name) {
            return None;
        }
        self.list().into_iter().find(|s| s.name == name)
    }

    /// Parses one skill folder; `None` when it has no readable `SKILL.md`.
    pub fn parse(&self, dir: &Path) -> Option<Skill> {
        let f = dir.join("SKILL.md");
        let text = std::fs::read_to_string(&f).ok()?;
        let (meta, body) = frontmatter(&text);
        let name = match meta.get("name").map(|s| s.trim()).filter(|s| !s.is_empty()) {
            Some(n) => n.to_string(),
            None => dir.file_name()?.to_string_lossy().to_string(),
        };
        if !valid_name(&name) {
            return None;
        }
        Some(Skill {
            name,
            description: meta.get("description").cloned().unwrap_or_default().chars().take(160).collect(),
            path: f,
            body: body.trim().to_string(),
        })
    }
}

/// Skill names are strict folder names: letters, digits, `_`, `-`.
pub fn valid_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 64
        && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

/// Splits an optional `---` frontmatter block from the body. Returns
/// (metadata lowercased keys, body).
pub fn frontmatter(text: &str) -> (std::collections::BTreeMap<String, String>, String) {
    let trimmed = text.trim_start_matches('\u{FEFF}').trim_start_matches('\n');
    if !trimmed.starts_with("---") {
        return (Default::default(), text.to_string());
    }
    let Some(end) = trimmed[3..].find("\n---") else {
        return (Default::default(), text.to_string());
    };
    let block = &trimmed[3..3 + end];
    let rest = &trimmed[3 + end + 4..];
    let mut meta = std::collections::BTreeMap::new();
    for line in block.trim().lines() {
        if let Some(idx) = line.find(':') {
            if idx > 0 {
                meta.insert(
                    line[..idx].trim().to_ascii_lowercase(),
                    line[idx + 1..].trim().to_string(),
                );
            }
        }
    }
    (meta, rest.trim_start_matches('\n').to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write_skill(root: &Path, name: &str, md: &str) {
        let dir = root.join(name);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("SKILL.md"), md).unwrap();
    }

    #[test]
    fn lists_project_skills_with_frontmatter() {
        let tmp = tempfile::tempdir().unwrap();
        write_skill(
            &tmp.path().join(".xencode").join("skills"),
            "commit-helper",
            "---\nname: commit-helper\ndescription: Writes conventional commits from the staged diff\n---\nRun `git diff --cached`.\n",
        );
        write_skill(
            &tmp.path().join(".xencode").join("skills"),
            "reviewer",
            "No frontmatter: the folder name is the skill name.\n",
        );
        let store = SkillStore::new(tmp.path());
        let skills = store.list();
        assert_eq!(vec!["commit-helper", "reviewer"], skills.iter().map(|s| s.name.as_str()).collect::<Vec<_>>());
        assert_eq!("Writes conventional commits from the staged diff", skills[0].description);
        assert!(skills[0].body.starts_with("Run `git diff --cached`"));
        assert_eq!("commit-helper", store.load("commit-helper").unwrap().name);
        assert!(store.load("../escape").is_none());
        assert!(store.load("missing").is_none());
    }

    #[test]
    fn global_skills_found_but_project_wins_by_name() {
        let tmp = tempfile::tempdir().unwrap();
        let fake_home = tempfile::tempdir().unwrap();
        std::env::set_var("USER_HOME_XENCODE", fake_home.path());
        write_skill(
            &fake_home.path().join(".xencode").join("skills"),
            "shared",
            "---\ndescription: global version\n---\nglobal",
        );
        write_skill(
            &fake_home.path().join(".xencode").join("skills"),
            "only-global",
            "---\ndescription: from the home folder\n---\nglobal",
        );
        write_skill(
            &tmp.path().join(".xencode").join("skills"),
            "shared",
            "---\ndescription: project version\n---\nproject",
        );
        let store = SkillStore::new(tmp.path());
        let skills = store.list();
        assert_eq!(vec!["only-global", "shared"], skills.iter().map(|s| s.name.as_str()).collect::<Vec<_>>());
        assert_eq!("project version", skills.iter().find(|s| s.name == "shared").unwrap().description);
        assert_eq!("global", store.load("only-global").unwrap().body);
        std::env::remove_var("USER_HOME_XENCODE");
    }

    #[test]
    fn frontmatter_parser_handles_missing_and_malformed_blocks() {
        let (empty, body) = frontmatter("Just a body.");
        assert!(empty.is_empty());
        assert_eq!("Just a body.", body);
        let (meta, body) = frontmatter("---\nname: a\ndescription: b\n---\nBody line.\n");
        assert_eq!(2, meta.len());
        assert_eq!("Body line.", body.trim());
        let (none, body) = frontmatter("---\nname: x\nbody continues");
        assert!(none.is_empty());
        assert!(body.starts_with("---"));
    }
}
