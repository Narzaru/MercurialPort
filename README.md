# hgrider

![Build](https://github.com/Narzaru/hgrider/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

<!-- Plugin description -->
**hgrider** brings practical Mercurial (hg) tools to JetBrains IDEs. It is a port of the
[HgVs](https://github.com/) Visual Studio extension to the IntelliJ Platform.

## Features

- **Hg Changes** tool window — browse modified, added, removed and untracked files with several
  comparison modes: uncommitted only, entire branch vs its parent, vs parent branch HEAD, or vs a
  custom branch. Filter/exclude the list, open a file, open its Mercurial diff (Ctrl + double-click),
  or revert selected files.
- **TODO mode** — scan TODO comments in changed files, including unsaved editor buffers, refreshed
  by polling.
- **Hg File History** tool window — `hg log -f` for the selected file (context menu → *Hg File
  History*), open a historical revision, and diff versions (vs previous, vs current file, or two
  selected revisions).
- **Hg Export** (Tools menu) — copy currently open editor files into a timestamped folder, or open
  that folder in Total Commander.

## Requirements

- The Mercurial command-line client available as `hg` on `PATH`.
<!-- Plugin description end -->

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "hgrider"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/Narzaru/hgrider/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


## License

[MIT](LICENSE) — do whatever you want with it; it comes with no warranty and the author carries no
liability for what it does.

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
