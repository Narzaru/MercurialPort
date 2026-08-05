<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# hgrider Changelog

## [Unreleased]

## [1.0.0] - 2026-08-05
### Added
- **One diff tab for the whole plugin** (`HgDiffTabManager`): Hg Changes and Hg File History used to keep
  a reusable tab each, so two diffs ended up on screen at once. The manager owns the tab, reuses it by
  content key, closes the previous one and falls back to a plain `openFile` when the editor has no tabs
  at all (with `focusEditor = false` the platform shows nothing in that case). A setting in
  `Settings → Tools → hgrider` switches back to a tab per window; sharing is on by default.
- Hg Changes: `Show Unchanged` toggle next to `Show Untracked` — files that ended up in the list without
  real changes are now hidden by default; the state is remembered per project.
- Hg Changes: the full path is shown in a tooltip for rows the renderer truncated with an ellipsis (and
  only for those). `TreeTable` has to be registered in `ToolTipManager` and asked via
  `getToolTipText(MouseEvent)` — a tooltip set on the reused renderer component is served from the wrong row.
- **Configurable encoding for hg output** (Settings → Tools → hgrider, also reachable from the Hg Changes
  ⋮ menu). Output is read as UTF-8 first and falls back to this encoding per line, so commit messages
  written in cp1251 are no longer rendered as question marks. Defaults to the OS ANSI codepage
  (`sun.jnu.encoding`) instead of `Charset.defaultCharset()`, which is UTF-8 on modern JDKs.
- **Review marks** in the Hg Changes tool window: a narrow eye column marks a file as reviewed.
  Click the eye (or press `Space` on the selection) to toggle, `Clear Reviewed` resets everything,
  and a `Reviewed: n/m` counter shows progress. Marks are stored per project and survive refresh/restart.

### Changed
- Hg Changes: `Open All` removed; `Clear Reviewed` uses `AllIcons.General.Reset` (a circular arrow reads as
  "reset the marks", unlike the previous uninstall icon); the `Filter/Exclude` row is hidden by default and
  its state is restored from settings alone — a non-empty saved filter no longer forces the row open.
- **Hg File History header now matches Hg Changes**: an icon toolbar (Refresh / Show Diff / Open) instead of
  buttons, small non-bold title and status in one row. `Diff Mode` is gone entirely (the enum with it) —
  one selected revision diffs against its parent, two selected revisions diff against each other, and
  comparing against the working copy is Hg Changes' job. The `Pin` checkbox is gone as well.
- The settings page is built with the UI DSL instead of `FormBuilder`: an `<html>` `JBLabel` without an
  explicit width lays out as a single line and ran past the dialog border. Long explanations now sit in
  `comment` under the field they describe.
- Hg File History: double click no longer opens the revision's file — every click just shows the diff.
  Extracting a revision stays available from the toolbar.
- **Hg Changes is now an Upsource-style tree** instead of a flat table: files are grouped into folders,
  single-child folder chains are collapsed into one row (`Cad.Toolware.Tests/RayTracing`), every file
  shows `+N −M`, and folders aggregate the counts plus a `reviewed/total` badge.
- The header is compact: an icon toolbar (Refresh / Undo / Clear Reviewed / Files / TODO / Untracked /
  Unchanged / Filter) that wraps instead of clipping, a mode combo box, hideable Filter/Exclude fields,
  and a single-line branch + summary row (`59 files +3665 −486 · 12/59 reviewed`).
- Exact per-file line counts come from `hg diff --git` (parsed once), which also replaces the previous
  `hg diff --stat` call used to detect files with no real changes.
- **Hg File History follows the active editor**: the panel is no longer empty until the context-menu
  action is used — it loads the history of the file currently open in the editor. Nothing is queried
  while the tool window is hidden.
- Hg File History reuses a single diff tab as well, titled with the file name only.
- **Hg File History click behaviour matches Hg Changes**: a click on a revision opens the diff
  (`Ctrl+double-click` is gone).
- Diffing a revision in Hg File History shows exactly what that revision changed: the left
  side is the file at the revision's first parent (`{p1rev}`), not at the next row of the log, which are
  different revisions for merges. Renames are followed — the parent side is read under the pre-rename
  path — and a revision that adds or deletes the file renders an empty side instead of an error dialog.
  Repeated clicks on the same revision reuse the open tab, and stale results from fast clicking are dropped.
- The "added" side of a history diff is an empty document of the same file type rather than
  `DiffContentFactory.createEmpty()`: with `EmptyContent` the tab refused to open for the revision that
  created the file (an initial commit, which has no parent, so the whole left side is empty).
- A failing `hg cat` now reports Mercurial's own stderr in the Hg File History status line (and the log)
  instead of silently doing nothing; the status line also shows which two sides are being compared.
- Hg File History no longer follows the editor onto its own diff tabs or onto the temporary file extracted
  by "Open": it only follows real files on disk, and editor selection changes caused by the panel itself
  are ignored. Previously opening a diff (or closing the previous one) made the panel reload the history
  of another file — the list flickered and emptied, the selected revision was lost, and the next
  `Open Diff` silently did nothing. Hg Changes suppresses the same following around its own diff tabs
  (`HgFileHistoryService.suppressFollow`), since closing a diff tab makes the editor select a neighbouring
  source file. Suppression covers editor events only — an explicit `syncTo` from Hg Changes is always honoured.
- Reloading the history keeps the previous rows on screen until the new ones arrive instead of blanking
  the table first, and out-of-order `hg log` results are dropped by a generation counter.
- Hg Changes click behaviour: **single click opens the diff** immediately, **double click opens the file**
  (the previous `Ctrl+double-click` shortcut for diff is gone).
- The diff now reuses a single editor tab instead of spawning a new one per file, keeps the focus in the
  file list, and the tab is titled with the file name only instead of the full path.
- The UTF-8 BOM returned by `hg cat` is stripped, so the first line of a diff is no longer reported as
  changed because of an invisible U+FEFF character.
- Added (`A`) and untracked (`?`) files diff against an empty base instead of failing with
  "Could not retrieve base version"; files missing locally (`R`) diff against an empty right side.
- Hg Changes table layout: the eye and `St` columns have a fixed width and no longer resize with the
  tool window — only `File Path` grows and shrinks.
- Hg Changes toolbar rows wrap onto several lines (`WrapLayout`) instead of being cut off when the tool
  window is docked vertically.

### Added (initial)
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- Ported Mercurial tooling from the HgVs Visual Studio extension:
  - **Hg Changes** tool window: modified/added/removed/untracked files with comparison modes
    (uncommitted, entire branch vs parent, vs parent branch HEAD, vs custom branch), filter/exclude,
    open, diff (Ctrl+double-click) and revert of selected files.
  - **TODO mode**: scans TODO comments in changed files, including unsaved editor buffers, with polling.
  - **Hg File History** tool window: `hg log -f` for a file, open a revision, diff modes
    (vs previous, vs current file, selected vs selected).
  - **Hg Export** actions: copy open editor files to a folder or open the folder in Total Commander.
