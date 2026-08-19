<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Mercurial Port Changelog

## [Unreleased]
### Changed
- README describes the comparison modes as they are now: their current titles, the fixed branch
  point against the moving parent HEAD, and the fact that both branch modes list the branch's own
  files only. Renames, the `Show ± Column` toggle and the real summary line were missing from it
  as well. The same mode wording went into the plugin description.
- Documentation and comments no longer present the plugin as a port of the Visual Studio
  extension: the two have diverged far enough that the references only misled.

## [1.0.4] - 2026-08-18
### Fixed
- **Branch modes list the branch's own files only — the same set Upsource shows.** Comparing against
  the branching point is right, but everything the parent branch changed before being merged in also
  differs from that point, and the list turned into 889 files and `+20585 −8168` where the review is
  89 files and `+6816 −144`. The file set is now taken from the revisions of the branch itself
  (`only(., max(ancestors(.) and branch(p1(first(branch(.))))))`) and the status output is filtered by
  it. A merge revision contributes only the files edited while merging, so conflict resolutions stay
  in the list and merely merged-in files stay out. `Branch changes vs parent HEAD` is filtered the
  same way — it now answers "my files against the parent branch as it is now" instead of dumping
  every change the parent has accumulated. An unrestricted comparison is still available through
  `vs branch`.

- **Double-clicking a directory no longer collapses and instantly re-expands it.** The tree column of
  a `TreeTable` hands its mouse events to the tree itself, which already toggles the node on a double
  click; the panel toggled it a second time. The panel's own toggle is kept for the other columns,
  where the event never reaches the tree.

### Changed
- **Comparison modes say what they compare against.** `Entire Branch (vs Parent)` and
  `Vs Parent Branch HEAD` differ only in the base revision — a fixed branch point against a parent
  head that moves with every commit of a colleague — and neither title said so. They are now
  `Branch changes (since branch point)` and `Branch changes vs parent HEAD`, each carrying a tooltip
  (on the list item and on the combo itself) that explains what the mode answers and that the list
  is limited to the branch's own files. The status line under the toolbar says `vs Branch point:`
  instead of `vs Parent:` for the same reason.

### Added
- **Renames are shown as renames.** `hg status` is asked for copy sources (`--copies`), and the
  removal of the old path plus the addition of the new one are folded into a single row marked `→`,
  with the source file name next to it and the full old path in the tooltip. The diff compares the
  file against its content under the old name in the base revision, so a moved file no longer reads
  as a deletion plus a wholly new file. A copy whose source is still present stays an addition but
  is diffed against the source too. Reverting a rename reverts the source path as well.

## [1.0.3] - 2026-08-07
### Added
- **Landing on a file marks it reviewed.** Selecting a row is what counts as reviewing it, so a
  single click *and* arrow-key navigation both show the diff and tick the eye — the review advances
  without a separate keystroke per file. `Mark Reviewed on Open` in the ⋮ menu (next to
  `Show ± Column`) turns the marking off and leaves the diff; on by default.
- Arrow navigation is debounced by 250 ms, so holding the key down to reach a file does not mark
  every row on the way — nor spawn an `hg cat` per row, of which only the last would be used.
  Mouse clicks stay immediate.

- **Base revision content is cached and prefetched.** `hg version` alone takes ~265 ms — that is
  Mercurial starting up, before any work; every `hg cat` pays it while the file read itself is free.
  Content of a revision never changes, so it is kept in an LRU cache (`HgContentCache`, negative
  answers included), and the neighbouring rows are read ahead, which makes the next diff ready
  before the arrow key is pressed. The cache is dropped on every refresh: the base revision is
  written symbolically (`.`) and points at different content after a commit.

### Changed
- Reviewed files are greyed out, not merely un-bolded — the same treatment as files with no real
  changes, since both are rows there is nothing left to do about.
- **Double click opens the file and no longer pulls the diff back on top.** The diff requested by
  the first of the two clicks is cancelled, so the editor ends up on the file instead of bouncing
  file → diff. The diff still opens if the two clicks are far enough apart to not read as a double
  click; avoiding that entirely would need a delay on every single click.
- Tree selection survives a rebuild: marking a file reviewed re-renders the whole tree, which used
  to drop the selection on every step of a review.

### Removed
- Hg Changes no longer follows the active editor. Closing the file and its diff made the editor fall
  back to a neighbouring tab, which the panel read as a deliberate open and answered with yet another
  diff — reopening what had just been closed. Nothing drives the panel now except the panel itself.
  `HgChangesService` and `ChangeLookup` went with it.
- `HgChangesPanel` shrank from ~1200 lines to ~900: everything that is not UI moved out of it.
  Parsing of `hg` output lives in `hg` (`HgStatusParser`, `HgDiffStatParser`, `HgLogParser`,
  `HgPaths`), review marks in `changes/ReviewState` behind a `ReviewedPathsStore`, settings keys in
  `changes/ChangesSettings`, filtering in `changes/PathFilter`, status-bar strings in
  `changes/StatusTextFormatter`, and the three cell renderers plus ellipsis fitting in
  `changes/ChangesTreeRenderers` and `changes/TextFitter`. Behaviour is unchanged; the point is that
  none of it could be covered by a test while it sat inside a Swing component.
- `HgFileHistoryPanel` and `FileExporter` use the shared `HgPaths`/`HgLogParser` instead of their own
  copies of path relativization and log parsing.
- `HgOutputDecoder.decode` takes an optional explicit fallback charset, so decoding can be tested
  without a running IDE; `HgSettings.fallbackEncoding` degrades to the system default when no
  application is available instead of throwing.
- Magic numbers in the panel became named constants (column widths, debounce and TODO poll delays,
  the revert confirmation preview limit), and `TodoParser`'s binary-file guard is now spelled
  `'\u0000'` — the raw character in the source was indistinguishable from a space.

### Added
- Test suite (94 JUnit tests) covering the extracted logic: status/diff/log parsing, encoding
  fallback and BOM handling, path normalization, the tree builder (directory collapsing, ordering,
  aggregates), review marks, filters, TODO scanning and ellipsis fitting. `./gradlew test` needs
  network access on a clean checkout — junit and the platform test framework are not vendored.

## [1.0.2] - 2026-08-06
### Fixed
- Hg Changes listed no removed files: `hg status` was called with `-ma` only, so every `R` entry was
  dropped and a branch showed fewer files than the same review on Upsource. Files deleted outside hg
  (`!`) are listed too, and share the red status colour with `R`.

## [1.0.1] - 2026-08-06
### Changed
- The plugin is called **Mercurial Port** in the IDE: Plugin Verifier rejects a descriptor whose name
  contains a JetBrains product name, and `hgrider` contains `rider`. The repository, the artifact and
  the zip keep the old name.
- Packages moved from `com.github.narzaru.hgrider` to `com.narzaru.mercurial`, and the plugin id with
  them. The IDE therefore treats this as a different plugin — uninstall the previous one.
- Settings page is now titled `Mercurial Port` (`Settings → Tools`).
- Stored setting keys, action ids and the distribution name follow the rename too: `hgrider.*` keys
  became `mercurial.*` and the artifact is `mercurial-port-<version>.zip`. Review marks, filters and
  the fallback encoding are therefore reset once — acceptable, the plugin has a single user.

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
