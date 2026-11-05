# Git Version Management

Open the Git page in a project workspace to manage code versions visually. There are three tabs at the bottom: Status, Branches, and Commits. If the current directory is not a Git repository, you can initialize an empty repository with one tap (default branch `main`) or enter a remote URL to clone an existing repository directly (**since 1.12.0**).

The key icon in the top-right corner opens the "Identity & Credentials" page, which contains the Identity and Credentials tabs—**identity must be configured before first use, or commits will fail**.

## Status

View the working tree status of the current repository.

The top card shows the current branch name, how many commits ahead or behind the remote it is (up/down arrows), and the counts of staged, modified, and untracked files.

**Commit** is the full-width primary button. It requires staged changes and a configured identity before it can be tapped; tapping it opens the commit message form.

The secondary actions are four buttons in a row:

- **Stage all**: adds all changes to the staging area.
- **Stash** (**since 1.12.0**): opens the stash list; see the "Stash" section below.
- **Pull / Push**: requires a configured remote. When the current branch has no upstream, pushing creates the association automatically.

Next to the group headers there are also:

- **Unstage all**: in the staged group; asks for confirmation before running. This only unstages—it never deletes file contents.
- **Revert all** (**since 1.11.0**): in the modified group; restores all unstaged changes to the last staged or committed content. Staged content is unaffected, but discarded changes cannot be recovered; asks for confirmation before running.

File changes are grouped into "Staged / Modified / Untracked":

- Staged: tap a row to view the diff between the staging area and the last commit; the "−" at the end of the row unstages.
- Modified: tap a row to view the diff between the working tree and the last commit; the "+" at the end stages the file. Deleted files are in this group too, with a red D status badge.
- Untracked: tap a row to view the new file's contents (shown entirely as additions); the "+" at the end stages. git folds newly created directories into one row (e.g. `feature/`); tap it to expand the files inside, then tap an individual file to view its contents.

If the working tree has uncommitted changes, tapping "Pull" first warns about possible conflicts and only proceeds after confirmation.

### The Commit Dialog (enhanced since 1.12.0)

In the dialog that opens when you tap "Commit changes":

- **AI generation**: tap the button to the right of the title bar and the AI reads the diff of the staging area and working tree, automatically generating a commit message that follows Conventional Commits. While generating, the button shows "Generating…"; if generation fails, it prompts you to check the model or network.
- **Quick types**: a row of chips above the input box (`feat`, `fix`, `refactor`, `chore`, `docs`, `style`, `perf`, `test`); tap one to prepend the corresponding prefix to the current message.
- **Amend last commit (--amend)**: a switch at the bottom; when on, this commit is merged into the previous one instead of creating a new one.

### Merge Conflicts (since 1.12.0)

If merging branches produces conflicts, the top of the Status page shows "Merge conflicts detected" and lists the conflicted files separately (with a magenta U status badge), prompting you to resolve them in the editor and stage, or tap "Abort merge" to give up this merge and restore the pre-merge state.

## Stash (since 1.12.0)

Stashing temporarily sets aside your current changes to bring the working tree back to a clean state, then takes them out again later. Tap the "Stash" button on the Status page to open the stash list:

- **Stash changes**: an optional one-line note can be entered, and "Include untracked files (-u)" can be checked to set new files aside too.
- **Pop**: takes out this stash and removes it from the stash list.
- **Apply**: takes out the contents but keeps the stash entry.
- **Drop**: deletes a single stash; asks for confirmation before running.
- **Clear all**: deletes all stashes; asks for confirmation before running.

Drop and Clear all are irreversible.

## Revert and Delete (since 1.11.0)

::: tip Version note
The long-press action menu on the Status page (Revert changes, Restore file, Delete untracked) and "Revert all" are available since 1.11.0; "Add to .gitignore" in the menu is available since 1.12.0.
:::

**Long-press any file row** to open the action menu from the bottom; the full path is shown at the top of the menu. The available actions depend on the group the file is in:

| Group | Menu items |
| --- | --- |
| Conflicted files | View diff, Stage, Copy path |
| Staged | View diff, Revert changes (restores to the last commit; both staged and unstaged changes are lost), Copy path |
| Modified | View diff, Revert changes (restores to the last staged or committed content), Copy path |
| Deleted files within Modified | View diff, **Restore file** (retrieves the file from the last commit), Copy path |
| Untracked files | View diff, **Add to .gitignore** (ignore this file / ignore similar files), Delete file, Copy path |
| Untracked directories | **Add to .gitignore** (ignore the whole directory), Delete directory, Copy path |

The two kinds of actions have completely different consequences—keep them straight:

- **Revert changes / Restore file**: for files Git has already tracked. Their content lives in the repository, so whether the file was deleted by you, by a terminal command, or by the AI, it can be brought back. The cost is losing this round's uncommitted changes.
- **Delete file / Delete directory**: only appears on untracked items. These files never entered the repository—**once deleted, Git cannot recover them**.

All revert and delete operations show a confirmation dialog before running, stating what will be lost.

## Branches

Displayed in four groups: HEAD, local branches, remote branches, and tags. The current branch card shows the branch name and the counts of the three kinds of refs. Local branches are shown as a path tree with the current branch highlighted.

**Switch branch**: tap any branch or tag and confirm to switch. Tapping a remote branch creates a local tracking branch and switches to it. Switching to a tag enters detached HEAD state (commits made in this state belong to no branch—avoid working in that state for long).

**Tap a branch row** to open the action menu:

- **Switch**: switches to this branch.
- **Merge branch** (**since 1.12.0**): merges this branch into the current branch; the confirmation dialog offers "Create a merge commit (--no-ff)". This item doesn't appear on the current branch itself.
- **Rename**: renames the local branch.
- **Delete**: local branches use safe delete—only merged branches can be deleted; unmerged ones are refused with a reason. Deleting a remote branch acts on the remote directly and is **irreversible**. The branch you are currently on cannot be deleted.

**New branch**: tap the + to the right of the local branches group header, enter a branch name and pick a base branch (the current branch by default); the "Create and switch" switch is on by default.

**Tags**: tap the + to the right of the tags group header to create a lightweight tag at the current HEAD; tap an existing tag → action menu → Delete to delete the local tag.

### Renaming Suggestion for the First Push of the master Branch (since 1.12.0)

If the current local branch is named `master` and has no upstream yet, the first push shows a dialog suggesting renaming it to `main` (the default primary branch on GitHub and GitLab). You can choose "Rename to main and push" or "Keep master and push".

## Commits

Shows recent commits with a colored branch topology. Each entry shows the short hash, author, relative time, commit message, and markers for the branch or tag it is on.

**Search filter** (**since 1.12.0**): there is a search box above the list; type a keyword to filter in real time by commit message, author, or short hash.

Tapping a commit opens a bottom detail panel: the full commit message, author and time, full hash (both the short and full hash can be copied), whether it is a merge commit, and the list of files changed. Tap a file to view that file's diff.

Above the detail panel there is also a row of advanced actions (**since 1.12.0**):

- **Branch**: creates a new branch based on this commit and switches to it.
- **Tag**: tags this commit.
- **Reset**: resets the current branch to this commit, with a choice of `Soft` (keep the working tree and index) or `Mixed` (keep the working tree, clear the index).

Scrolling near the bottom loads earlier commits automatically; "No more" is shown once everything is loaded.

## Diff View

Tapping a file or commit to view its diff opens the standalone diff page:

- Diffs are shown as numbered `+ / −` colored lines; the top bar can **open in editor** (**since 1.12.0**), jumping straight to that file in the code editor.
- When a single side of the file exceeds 2MB, it just says "File too large" and shows no content (same for binary files).

## Oversized Output

To protect the phone's memory, the Git page never reads oversized content in full: when reading git output for the status, branch, and commit lists exceeds the limit, it shows "Output too large, truncated—narrow the scope and try again" and stops trying to load.

## Common Error Messages

When an operation fails, Git's raw error is translated into a friendly message:

| Scenario | Message |
| --- | --- |
| Deleting the branch you are on | Cannot delete the current branch; switch to another branch first |
| Deleting a branch with unmerged commits | This branch has unmerged commits and cannot be safely deleted |
| Switching to a nonexistent branch | Branch or reference does not exist |
| Local changes would be overwritten by switching | Uncommitted local changes would be overwritten; commit or stash them before switching |
| Push rejected (remote has updates) | Push rejected—the remote has newer commits; pull first |
| Remote authentication failed | Remote authentication failed; check your credentials (username/password/token) |
| Commit identity not configured | Commit identity is not configured; fill in the user name and email in settings |
| No remote configured | No remote repository configured; cannot push/pull |
