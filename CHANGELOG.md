# Changelog

All notable user-facing changes to the Jupyter Notebook plugin are documented here.

## [0.3.0] - 2026-08-30

### Added

- Start a new IPython kernel or attach to an existing classic Jupyter kernel using either a connection file or manual connection parameters.
- Optional source-interpreter selection for attached kernels, so navigation resolves against the environment that owns the kernel.
- `Cmd`/`Ctrl`+Click and `Cmd`/`Ctrl`+B navigation from notebook code to lexical definitions, project files, Python standard-library modules, and installed packages.
- Rich kernel output handling for display updates, deferred output clearing, images, HTML, tracebacks, and multiple MIME representations.
- Regression and integration coverage for notebook serialization, editor dispatch, navigation, kernel protocol, connection settings, and kernel lifecycle behavior.

### Fixed

- Corrected vertical caret alignment after reopening notebooks, including empty, trailing-newline, wrapped, and multiline cells.
- Hid the text caret in command mode and made single-click selection, double-click editing, and `Escape` transitions consistent.
- Repaired Markdown edit/preview behavior and rendering for headings, emphasis, links, inline and fenced code, ordered and unordered lists, block quotes, tables, escaped pipes, horizontal rules, and notebook attachments; unsafe markup and URLs are sanitized.
- Preserved raw cells, cell and notebook metadata, attachments, rich MIME outputs, and explicit `null` execution counts across load/save cycles.
- Fixed kernel startup, stop, restart, reconnect, stale-message, stale-callback, and process-ownership races.
- Prevented duplicate executions while a cell is already running and made sequential Run All behavior deterministic.
- Fixed interpreter auto-detection updates inside the modal kernel connection dialog.
- Hardened notebook analysis and navigation for Unicode identifiers, IPython magics and shell commands, ambiguous bindings, and unavailable Python helpers.
- Replaced quadratic Markdown delimiter scanning with a linear implementation for large cells.

### Changed

- Kernel communication now uses an actor-owned JeroMQ transport with signed Jupyter messages, control-channel readiness checks, and heartbeat monitoring.
- Stopping an attached kernel now disconnects locally without terminating the external process or deleting its connection file.

## 0.2.3

- Added automatic Light/Darcula theme synchronization for the notebook editor.

## 0.2.2

- Recovered from transient bundled-resource read failures and displayed an actionable error if retrying could not restore the editor.

## 0.2.1

- Added notebook-wide Find and Replace with highlighted matches and correct Unicode offsets.

## 0.2.0

- Added inline diagnostics, go-to-definition for notebook symbols, editor shortcuts, run-and-advance, and the `.ipynb` file icon.

## 0.1.0

- Initial release with notebook editing, Markdown cells, code execution, rich outputs, and basic kernel lifecycle management.
