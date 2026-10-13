# Prompt History for Claude Code

A JetBrains IDE plugin (PhpStorm, IntelliJ IDEA, … 2026.2+) that adds a **Prompt History**
tool window: a chronological list of your prompts to [Claude Code](https://claude.com/claude-code),
each with the first line of Claude's final answer.

```
▾ Fix login redirect                     14:20 · 3 · /inc
  ▾ 14:02  /plan-lite fix the redirect after login
      Plan written to TODO.md, 4 acceptance criteria
  ▾ 14:11  /inc
      Added a failing test and the fix, suite is green
  ▾ 14:20  why does the old session cookie survive?
      The cookie is set on the parent domain, so …
```

## Why

I run six or more Claude Code sessions at once, each in its own IDE terminal tab, each
on a different task. When I come back to a tab, I need to know where that task stands:
was the plan written, which increment is done, is it in review or in fixes? Scrolling the
terminal back is slow, `claude --resume` lists sessions but not what happened inside them,
and keeping notes in a scratch file by hand did not last.

So this panel answers one question for the tab I am looking at: *what have I asked here so
far, and what did Claude do about it?* One line per prompt, one line per answer, and the
last slash command in the session row as the current phase of the task. It is a quick
overview, not a conversation viewer.

> Unofficial. Not affiliated with or endorsed by Anthropic. A personal tool shared as is:
> it reads undocumented files written by Claude Code, which may change at any time.

## What it shows

- **The session of the active terminal tab.** Switch IDE terminal tabs and the panel
  follows the Claude Code session running in that tab (within half a second). A tab
  without Claude keeps the last shown session.
- **All Sessions** (button in the tool window header): every session of the open project,
  newest first.
- Slash commands are shown as `/name args` with the name highlighted, shell input as `! cmd`.
- The session row ends with the last slash command used in it, so you can see the phase
  of the task (`/plan` → `/inc` → `/review` …) even with the session collapsed.
- Noise is left out: system and meta messages, subagents, compaction summaries, agent
  notifications, local command output, `/clear`, `/exit`, interruptions, and automated
  sessions started through the SDK.
- Prompts typed while Claude is still working and prompts made of pasted text are included.

## How it works

Read-only; the plugin writes no files and needs no hooks.

- Transcripts: `~/.claude/projects/<slug>/<session>.jsonl`, where the slug is the project
  path with non-alphanumeric characters replaced by `-`. Only appended lines are read,
  every 3 seconds. `CLAUDE_CONFIG_DIR` is respected.
- Active tab → session: the shell PID of the selected terminal tab, its descendant process
  that has a `~/.claude/sessions/<pid>.json` registration, and the `sessionId` in it.

## Limitations

- Only sessions started in the project root are found (the slug is derived from the
  project path).
- Following the terminal tab uses the experimental (partly internal) API of the new
  IDE terminal. If an IDE update breaks it, a warning goes to `idea.log` and the panel
  falls back to the most recently active session.
- Tested on Linux with PhpStorm 2026.2.

## Similar tools

Other JetBrains plugins cover parts of this; pick whichever fits your workflow.

- [Seshlog](https://plugins.jetbrains.com/plugin/33850-seshlog--coding-agent-session-manager):
  session manager for Claude Code and other agents with full-text search, conversation
  preview, resume/fork, and jumping to a session's terminal tab.
- [Claude Code Sessions](https://plugins.jetbrains.com/plugin/31134-claude-code-sessions):
  live monitor of running sessions (status, model, context); uses the same
  `~/.claude/sessions/<pid>.json` registration.
- [Recap](https://plugins.jetbrains.com/plugin/32389-recap--ai-agent-sessions),
  [Clauditor](https://plugins.jetbrains.com/plugin/30981),
  [Claudia](https://plugins.jetbrains.com/plugin/29889-claudia): browse, search and
  resume past sessions.

What this plugin does differently: the panel follows the active terminal tab on its own,
and it shows a compact list of your prompts with one line of each answer, as an overview
of what you did where rather than a full conversation viewer.

## Install

Download the zip from [Releases](https://github.com/michalcampr/prompt-history-for-claude-code/releases) (or build it), then *Settings → Plugins → ⚙ →
Install Plugin from Disk…*.

## Build

Requires JDK 25.

```bash
./gradlew test buildPlugin          # → build/distributions/*.zip
```

By default Gradle downloads PhpStorm (`platformVersion` in `gradle.properties`) once to
compile against. To use an IDE you already have, pass its install directory:

```bash
./gradlew -PplatformPath=/path/to/your/ide buildPlugin
```

(`platformPath=…` can also go to `~/.gradle/gradle.properties`.)

## License

[MIT](LICENSE)
