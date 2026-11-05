package com.xencode.feature.agent.domain.permission

/**
 * Built-in safe allowlist: command prefixes that are common and verifiably "read-only, no side effects, no network, no child-process spawning" — auto-allowed without a dialog.
 *
 * This list is code-built-in and immutable — deliberately never written to DataStore, let alone `~/workspace`, matching the authorization layer's
 * "rules must never be persisted inside the container" security principle: the in-container AI cannot read or write this table to escalate itself.
 *
 * Design principles (keep them):
 * - Only commands that are "read-only no matter the arguments". Anything that can write files, change state, go online, or spawn and run a child process is excluded.
 * - Spawn-and-run categories are absolutely excluded: `node`/`python`/`ruby`/`sh -c`/`xargs`/`find -exec`/`env CMD`/`command CMD`/
 *   `source`/`.`/`eval`/`sed -i`/`awk system()` — otherwise `env rm -rf /` and friends would slip through prefix rules.
 * - Writable commands are excluded: `find`(supports -exec/-delete), `sed`/`awk`(support -i/system), `tee`, `cp`/`mv`/`rm`/`ln`/
 *   `mkdir`/`rmdir`/`chmod`/`chown`/`touch`/`dd`.
 * - Network commands are excluded: `curl`/`wget`/`ssh`/`scp`/`rsync`.
 * - Secret-leaking/spawn-capable ones are excluded: `env`/`printenv`(leaks env vars and `env CMD` spawns).
 * - git only admits subcommands that are "read-only with any arguments"; pull/clone/push/commit/add/reset/checkout/branch/tag/merge/rebase are excluded.
 *
 * Matching uses [ShellCommandParser.matches]'s token-prefix semantics: `git status` matches `git status -s` but not `git push`.
 *
 * Safety fallback: users can add DENY rules in the management UI to override this allowlist (DENY outranks every ALLOW during evaluation, built-ins included).
 * Commands that cannot be statically judged (command substitution/grouping/absolute-path redirection) skip this allowlist and still get the dialog.
 */
object BuiltInSafeCommands {

    /** git subcommand prefixes that are "read-only with any arguments"; mutating subcommands are not listed and still need the dialog. */
    private val GIT_READ_ONLY_PREFIXES: List<String> = listOf(
        "git status", "git log", "git diff", "git show", "git blame",
        "git rev-parse", "git ls-files", "git describe", "git for-each-ref",
        "git cat-file", "git ls-tree", "git config --get",
        "git stash list", "git remote -v"
    )

    /** Safe command prefixes auto-allowed (token-prefix matched). */
    val SAFE_PREFIXES: List<String> = listOf(
        // —— Directory/file metadata viewing (read-only) ——
        "ls", "pwd", "tree", "stat", "file", "du", "df",
        "basename", "dirname", "realpath", "readlink", "lsof",
        // —— File content viewing (read-only; relative redirection writing workspace files is deemed acceptable by ShellCommandParser's established policy) ——
        "cat", "head", "tail", "less", "more", "wc", "nl", "tac", "paste", "column",
        // —— Search (read-only) ——
        "grep", "egrep", "fgrep", "rg", "ack", "zgrep",
        // —— Text processing (read-only, output to stdout; no sed/awk — both can modify files via -i/ system) ——
        "sort", "uniq", "cut", "tr", "fold", "fmt", "expand", "unexpand", "rev", "comm", "diff", "cmp",
        // —— Info/lookup/system monitoring (read-only; no env/printenv — they leak env vars and can spawn execution) ——
        "echo", "printf", "date", "whoami", "id", "uname", "hostname", "uptime", "nproc", "cal",
        "which", "type", "whereis", "locate", "getconf",
        "ps", "top", "free", "htop", "vmstat", "lscpu", "lsblk", "arch", "clear", "history", "true", "false"
    ) + GIT_READ_ONLY_PREFIXES

    /** Whether this segment matches any built-in safe prefix. */
    fun isSafe(segmentTokens: List<String>): Boolean =
        SAFE_PREFIXES.any { ShellCommandParser.matches(it, segmentTokens) }
}
