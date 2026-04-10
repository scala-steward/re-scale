/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Default rule set, equivalent (byte-for-byte where it matters) to
 * the legacy ssg-dev `RuleEngine.scala` hardcoded logic. This is the
 * baseline a fresh re-scale install ships with; per-repo
 * `.claude-hook.yaml` overrides take precedence.
 *
 * Rule categories (matching the legacy):
 *   1. Trusted programs (allow)
 *   2. Destructive operations (deny)
 *   3. Suboptimal tools that should redirect to dedicated Claude tools (deny)
 *   4. Git granular rules (allow read, deny destructive, ask unknown)
 *   5. GitHub CLI granular rules (allow read, ask state-mutating)
 *   6. Safe utilities (allow)
 *   7. Self-tool pipe filters (deny pipes from re-scale to head/grep/etc)
 *   8. System file write protection (deny redirects to /etc/, /usr/, etc.)
 *   9. Secret file protection (deny .env, .pem, .key, credentials*)
 *  10. JAR-grep evasion (deny `curl ... .jar | grep` patterns)
 */
package rescale.hook

import rescale.hook.Rule.*
import rescale.hook.Rule.Condition as C

object DefaultRules {

  /** The canonical re-scale rule set. The first matching rule wins. */
  val ruleSet: RuleSet = RuleSet(
    List(
      // ============================================================
      // Self-tool program — always allowed (the user MUST be able to
      // call re-scale itself).
      // ============================================================
      RuleEntry(
        when   = C.ProgramIn(List("re-scale", "ssg-dev", "sge-dev")),
        action = Some(Decision.Allow)
      ),

      // ============================================================
      // Trusted build / package / runtime tools.
      // ============================================================
      RuleEntry(
        when   = C.ProgramIn(List("scala-cli", "cs", "coursier", "npm", "npx", "cargo")),
        action = Some(Decision.Allow)
      ),
      RuleEntry(
        when = C.And(List(
          C.ProgramIn(List("java")),
          C.HasAny(List("-version", "--version"))
        )),
        action = Some(Decision.Allow)
      ),
      RuleEntry(
        when = C.And(List(
          C.ProgramIn(List("sbt")),
          C.HasAny(List("--client"))
        )),
        action = Some(Decision.Allow)
      ),
      RuleEntry(
        when   = C.ProgramIn(List("sbt")),
        action = Some(Decision.Deny("Use 'sbt --client' instead of bare 'sbt' (hangs on build.sbt errors)"))
      ),

      // ============================================================
      // Destructive: rm with -r/-rf/-f
      // ============================================================
      RuleEntry(
        when = C.And(List(
          C.ProgramIn(List("rm")),
          C.Or(List(
            C.HasAny(List("-r", "-rf", "-fr", "-f", "-R", "--recursive", "--force"))
          ))
        )),
        action = Some(Decision.Deny("Destructive: rm with -r/-rf/-f flags requires human confirmation"))
      ),
      RuleEntry(
        when   = C.ProgramIn(List("rm")),
        action = Some(Decision.Deny("rm requires confirmation — use re-scale or a safer alternative"))
      ),

      // ============================================================
      // HTTP mutation via curl/wget — bypasses gh / re-scale gates
      // ============================================================
      RuleEntry(
        when = C.And(List(
          C.ProgramIn(List("curl", "wget")),
          C.HasAny(List("-X", "--request", "--data", "-d", "--data-binary", "--data-raw"))
        )),
        action = Some(Decision.Deny("HTTP mutation via curl/wget — use re-scale git gh instead"))
      ),

      // ============================================================
      // Process killers
      // ============================================================
      RuleEntry(
        when   = C.ProgramIn(List("kill", "killall", "pkill")),
        action = Some(Decision.Deny("Process kill — use 're-scale proc list' and 're-scale proc kill' for safe management"))
      ),

      // ============================================================
      // adb — Android Debug Bridge. Deny by default because adb
      // commands can flash firmware, wipe user data, install APKs,
      // and shell into a device. Originally from sge-dev's RuleEngine.
      // Projects that need adb can allow it via .rescale/claude-hooks.yaml.
      // ============================================================
      RuleEntry(
        when   = C.ProgramIn(List("adb", "fastboot")),
        action = Some(Decision.Deny("adb/fastboot commands require explicit opt-in via .rescale/claude-hooks.yaml"))
      ),

      // ============================================================
      // System-directory write protection — every flavor's RuleEngine
      // had this. Refuses redirects to /etc/, /usr/, /System/, or
      // /Library/ regardless of which command is doing the writing.
      // Catches `echo foo > /etc/bar`, `program > /usr/local/bin/x`,
      // and similar accidental sudo-like writes.
      // ============================================================
      RuleEntry(
        when   = C.HasRedirectTargetPrefix(List("/etc/", "/usr/", "/System/", "/Library/")),
        action = Some(Decision.Deny("Refusing to write under a system directory (/etc, /usr, /System, /Library)"))
      ),

      // ============================================================
      // Secret file protection — every flavor had this safety rail.
      // Denies any command whose argv mentions a `.env`, `.pem`,
      // `.key`, `credentials.*`, or generic `secret` substring. The
      // re-scale binary itself is exempt (it manages skip-policy.tsv
      // and similar files that may legitimately match).
      // ============================================================
      RuleEntry(
        when = C.And(List(
          C.Not(C.ProgramIn(List("re-scale", "ssg-dev", "sge-dev"))),
          C.Or(List(
            C.HasAnySuffix(List(".env", ".pem", ".key")),
            C.HasAnyContains(List("/.env", "credentials.", "secret"))
          ))
        )),
        action = Some(Decision.Deny("Potential secret-file access (.env / .pem / .key / credentials / secret)"))
      ),

      // ============================================================
      // Suboptimal tools — redirect to dedicated Claude tools
      // ============================================================
      //
      // The pipe-to-filter rule MUST come first so that pipelines like
      // `find . | head` get the more informative "write to file" message
      // instead of the per-program rule's standalone message.
      //
      // Pipe-to-filter rule: deny when ANY command is followed in a
      // pipeline by `head` / `tail` / `wc` / `grep` / `rg`. Filtering
      // another program's output via these tools is the anti-pattern;
      // the alternative is to write the upstream output to a file
      // (`cmd > /tmp/out`) and then Read it (or use the Grep tool for
      // filtering). This pushes the agent toward intermediate files
      // that can be re-inspected, instead of one-shot pipelines that
      // truncate output the agent will then have to re-run.
      RuleEntry(
        when   = C.FollowedBy(C.ProgramIn(List("head", "tail", "wc", "grep", "rg", "ripgrep"))),
        action = Some(Decision.Deny(
          "Don't pipe to head/tail/wc/grep — write the upstream output to a file " +
          "(e.g. `cmd > /tmp/out`) and then read it with the Read tool, or use the " +
          "Grep tool for filtering. Pipelines truncate output you may need to re-inspect."
        ))
      ),
      RuleEntry(
        when   = C.ProgramIn(List("grep", "rg", "ripgrep")),
        action = Some(Decision.Deny("Use the Grep tool instead of grep/rg"))
      ),
      RuleEntry(
        when   = C.ProgramIn(List("find")),
        action = Some(Decision.Deny("Use the Glob tool instead of find"))
      ),
      RuleEntry(
        when   = C.ProgramIn(List("ls")),
        action = Some(Decision.Deny("Use the Glob tool instead of ls"))
      ),
      // `cat` is universally safe — the dangerous case (`cat secret.env`,
      // `cat /etc/passwd`, etc.) is already covered by the secret-file
      // rule above + the system-directory redirect rule. Standalone
      // `cat foo.txt`, `cat <<EOF` heredoc passthrough, `cat -` for
      // stdin pipelines, `git commit -F -` patterns, etc. should all
      // flow through. No rule needed here.
      RuleEntry(
        when = C.And(List(
          C.ProgramIn(List("echo")),
          C.Or(List(
            C.HasRedirect(">"),
            C.HasRedirect(">>")
          ))
        )),
        action = Some(Decision.Deny("Use the Write or Edit tool instead of echo redirect"))
      ),
      RuleEntry(
        when   = C.ProgramIn(List("sed", "awk", "perl")),
        action = Some(Decision.Deny("Use the Edit tool instead of sed/awk/perl"))
      ),
      // Note: `wc` was previously in this list but it's been moved to
      // the pipe-to-filter rule above. Standalone `wc -l foo.txt` is
      // fine; only `cmd | wc -l` is the anti-pattern.
      RuleEntry(
        when   = C.ProgramIn(List("sort", "uniq", "cut", "tr", "xargs")),
        action = Some(Decision.Deny("Use dedicated tools or re-scale flags instead of sort/uniq/cut/tr/xargs"))
      ),
      RuleEntry(
        when   = C.ProgramIn(List("python", "python3", "ruby", "node")),
        action = Some(Decision.Deny("Ad-hoc scripting not allowed — use re-scale utilities instead"))
      ),

      // ============================================================
      // JAR / archive grep evasion (download a binary, extract, grep)
      // ============================================================
      RuleEntry(
        when = C.And(List(
          C.ProgramIn(List("curl", "wget")),
          C.HasAnySuffix(List(".jar", ".tar.gz", ".tgz", ".zip"))
        )),
        action = Some(Decision.Ask("Downloading archives to grep through them bypasses MCP/build tools — confirm intent"))
      ),

      // ============================================================
      // Git granular rules (single composite RuleEntry per category)
      // ============================================================
      RuleEntry(
        when = C.And(List(
          C.ProgramIn(List("git")),
          C.Or(List(
            C.StartsWith(List("git", "status")),
            C.StartsWith(List("git", "diff")),
            C.StartsWith(List("git", "log")),
            C.StartsWith(List("git", "show")),
            C.StartsWith(List("git", "blame")),
            C.StartsWith(List("git", "ls-files")),
            C.StartsWith(List("git", "ls-tree")),
            C.StartsWith(List("git", "cat-file")),
            C.StartsWith(List("git", "rev-parse")),
            C.StartsWith(List("git", "describe")),
            C.StartsWith(List("git", "shortlog")),
            C.StartsWith(List("git", "reflog")),
            C.StartsWith(List("git", "name-rev")),
            C.StartsWith(List("git", "merge-base")),
            C.StartsWith(List("git", "grep")),
            // git config read-only forms (--get/--list/--edit) are
            // explicitly allowed here. The companion deny rule below
            // catches every OTHER `git config` invocation as a write.
            C.And(List(
              C.StartsWith(List("git", "config")),
              C.HasAny(List("--get", "--get-all", "--get-regexp", "--list", "-l", "-e", "--edit"))
            ))
          ))
        )),
        action = Some(Decision.Allow)
      ),
      RuleEntry(
        when = C.And(List(
          C.ProgramIn(List("git")),
          C.Or(List(
            C.StartsWith(List("git", "reset")),
            C.StartsWith(List("git", "restore")),
            C.StartsWith(List("git", "clean")),
            C.StartsWith(List("git", "rebase"))
          ))
        )),
        action = Some(Decision.Deny("git reset/restore/clean/rebase rewrites or destroys local state"))
      ),
      RuleEntry(
        when = C.And(List(
          C.StartsWith(List("git", "commit")),
          C.HasAny(List("--amend"))
        )),
        action = Some(Decision.Deny("git commit --amend overwrites the previous commit — create a new commit instead"))
      ),
      RuleEntry(
        when = C.And(List(
          C.StartsWith(List("git", "push")),
          C.HasAny(List("--force", "-f", "--force-with-lease"))
        )),
        action = Some(Decision.Deny("Force push overwrites remote history"))
      ),
      RuleEntry(
        when = C.And(List(
          C.StartsWith(List("git", "branch")),
          C.HasAny(List("-d", "-D", "--delete", "-m", "-M", "--move"))
        )),
        action = Some(Decision.Deny("git branch delete/rename overwrites data"))
      ),
      // git config writes overwrite settings — only allow read-only forms.
      // Backported from sge's RuleEngine.
      RuleEntry(
        when = C.And(List(
          C.StartsWith(List("git", "config")),
          C.Not(C.HasAny(List("--get", "--get-all", "--get-regexp", "--list", "-l", "-e", "--edit")))
        )),
        action = Some(Decision.Deny("git config overwrites settings — read with --get/--list only"))
      ),
      RuleEntry(
        when = C.And(List(
          C.StartsWith(List("git", "tag")),
          C.HasAny(List("-d", "--delete", "-f", "--force"))
        )),
        action = Some(Decision.Deny("git tag delete/force overwrites data"))
      ),
      RuleEntry(
        when = C.And(List(
          C.ProgramIn(List("git")),
          C.Or(List(
            C.StartsWith(List("git", "add")),
            C.StartsWith(List("git", "commit")),
            C.StartsWith(List("git", "fetch")),
            C.StartsWith(List("git", "pull")),
            C.StartsWith(List("git", "clone")),
            C.StartsWith(List("git", "init")),
            C.StartsWith(List("git", "cherry-pick")),
            C.StartsWith(List("git", "merge")),
            C.StartsWith(List("git", "switch")),
            C.StartsWith(List("git", "branch")),
            C.StartsWith(List("git", "tag")),
            C.StartsWith(List("git", "push"))
          ))
        )),
        action = Some(Decision.Allow)
      ),
      RuleEntry(
        when   = C.ProgramIn(List("git")),
        action = Some(Decision.Ask("Unknown git subcommand — confirm with user"))
      ),

      // ============================================================
      // GitHub CLI (gh) granular rules
      // ============================================================
      RuleEntry(
        when = C.And(List(
          C.ProgramIn(List("gh")),
          C.Or(List(
            C.StartsWith(List("gh", "pr", "list")),
            C.StartsWith(List("gh", "pr", "view")),
            C.StartsWith(List("gh", "pr", "diff")),
            C.StartsWith(List("gh", "pr", "checks")),
            C.StartsWith(List("gh", "pr", "status")),
            C.StartsWith(List("gh", "issue", "list")),
            C.StartsWith(List("gh", "issue", "view")),
            C.StartsWith(List("gh", "issue", "status")),
            C.StartsWith(List("gh", "release", "list")),
            C.StartsWith(List("gh", "release", "view")),
            C.StartsWith(List("gh", "run", "list")),
            C.StartsWith(List("gh", "run", "view")),
            C.StartsWith(List("gh", "repo", "view")),
            C.StartsWith(List("gh", "api"))
          ))
        )),
        action = Some(Decision.Allow)
      ),
      RuleEntry(
        when = C.And(List(
          C.ProgramIn(List("gh")),
          C.Or(List(
            C.StartsWith(List("gh", "pr", "create")),
            C.StartsWith(List("gh", "pr", "merge")),
            C.StartsWith(List("gh", "pr", "close")),
            C.StartsWith(List("gh", "issue", "create")),
            C.StartsWith(List("gh", "issue", "close"))
          ))
        )),
        action = Some(Decision.Ask("Shared-state action on GitHub — confirm with user before running"))
      ),

      // ============================================================
      // Safe utilities — explicitly allowed
      // ============================================================
      RuleEntry(
        when = C.ProgramIn(List(
          "cd", "pwd", "which", "type", "command", "test", "[", "true", "false",
          "mkdir", "env", "printenv", "date", "uname", "arch", "hostname",
          "file", "otool", "ldd", "dumpbin",
          "tar", "unzip", "gzip", "gunzip",
          "mv", "cp", "ln", "touch", "chmod",
          "codesign", "install_name_tool",
          "sleep", "tee", "echo", "printf",
          "export", "source", ".", "set", "unset", "local", "declare", "readonly"
        )),
        action = Some(Decision.Allow)
      ),

      // ============================================================
      // Catch-all: anything not explicitly listed → allow.
      // (Equivalent to the legacy `case _ => Decision.Allow`.)
      // ============================================================
      RuleEntry(
        when   = C.True,
        action = Some(Decision.Allow)
      )
    )
  )
}
