# Prompt History for Claude Code: notes for AI assistants

JetBrains IDE plugin (Kotlin, IntelliJ Platform 2026.2 / build 262). User-facing docs are in
`README.md`; this file holds what the code does not tell you.

## Build and test

```bash
./gradlew test buildPlugin                                  # downloads PhpStorm once (~10 min)
./gradlew -PplatformPath=/path/to/installed/ide test buildPlugin   # no download
```

- Needs a full **JDK 25** (the platform requires it). A JRE is not enough: Gradle toolchain
  detection then fails with "Cannot find a Java installation … languageVersion=25".
- Output: `build/distributions/prompt-history-for-claude-code-<version>.zip`.
- Tests are plain JUnit 4 and run headless, including the Swing tree (`HistoryPanel`, `Renderer`).
  Write the failing test first; most bugs so far were found only on real data, so when a change
  touches parsing, also run the parser over real transcripts with a throwaway test and delete it.

## Architecture

| File | Role |
|---|---|
| `SessionParser.kt` | One transcript, fed line by line; produces `Session` with `Turn`s (prompt, first line of the answer). Pure, no IDE dependencies. |
| `TranscriptStore.kt` | Project dir `~/.claude/projects/<slug>/`; incremental reads (offset per file, unfinished last line waits); top 30 sessions **with prompts**, by mtime. |
| `ClaudeProcesses.kt` | Shell PID → descendant with `~/.claude/sessions/<pid>.json` → `sessionId`. |
| `TerminalFocus.kt` | PID of the shell in the selected IDE terminal tab. The only file using the terminal API. |
| `HistoryToolWindowFactory.kt` | Tool window, `HistoryPanel` (polling, focus, incremental tree), `Renderer`. |

Threads: the store and process lookup run on the background poller (fixed delay, never
concurrent); tree changes only on the EDT with immutable snapshots.

## Things that are not obvious

**Transcript format** (undocumented, can change with any Claude Code release):

- A prompt typed while Claude works is not a `user` entry but
  `attachment` with `attachment.type == "queued_command"`; only `origin.kind == "human"` is
  the user (the rest are task notifications).
- Pasted text arrives wrapped in `<pasted_content …>…</pasted_content …>` and must be
  unwrapped before the "starts with `<` = system message" rule.
- SDK sessions (`entrypoint` `sdk-*`, `promptSource: "sdk"`) are automation, not the user.
- `model: "<synthetic>"` assistant messages ("No response requested.") must not overwrite
  the real answer; `isApiErrorMessage` ones should.
- A line may be valid JSON with unexpected shapes (`"message": null`); one bad line must
  never stop reading the file or duplicate turns. Log once per session, not per line.
- The project slug is the path with every non-alphanumeric character replaced by `-`.

**IntelliJ Platform:**

- The terminal API (`TerminalToolWindowTabsManager`, `TerminalView.startupOptionsDeferred`,
  `TerminalStartupOptions.pid`) is `@Experimental`, `TerminalView` also `@Internal`. Keep it
  in `TerminalFocus.kt`, catch `Throwable` around it, and degrade to "most recent session".
- `intellij.terminal.frontend` is a content module with its own classloader, hence
  `<dependencies><module name="intellij.terminal.frontend"/>` in `plugin.xml`, not just
  a plugin dependency.
- IntelliJ `Tree.collapsePath` on a session first fires `treeCollapsed` for each expanded
  child and **never fires `treeWillCollapse`**. Do not infer "the user collapsed this prompt"
  from events; that is why manually collapsed prompts are not remembered.
- Never rebuild the tree model on refresh (`reload()`): it drops selection and expansion.
  Use `insertNodeInto` / `nodeChanged` / `removeNodeFromParent`. If the hidden root ends up
  with no children it collapses and must be expanded again.
- `ColoredTreeCellRenderer` keeps `toolTipText` between rows; reset it on every call.

## Conventions

- No absolute user paths in tracked files; use `user.home` / `CLAUDE_CONFIG_DIR` at runtime.
- Bump `version` in `build.gradle.kts` for every release so the IDE treats it as an update.
- Comments and user-visible strings in English.
