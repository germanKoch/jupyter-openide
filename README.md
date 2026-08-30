# Jupyter Notebook Plugin for IntelliJ

Open, edit, and execute `.ipynb` notebooks directly inside IntelliJ-based IDEs. The plugin can launch an IPython kernel from the project's Python environment or attach to an already running classic Jupyter kernel over ZeroMQ.

## Features

- Native notebook editor with code and markdown cells
- Python syntax highlighting with cross-cell variable awareness
- Cell execution via Shift+Enter / Cmd+Enter
- Kernel lifecycle management (launch, attach by connection file/JSON, stop, reconnect/restart, interrupt)
- Auto-detection of the project's Python interpreter and `ipykernel`
- Cmd/Ctrl+Click source navigation across notebook cells and into project, standard-library, and installed-library Python source
- Rich output rendering (text, HTML, images, errors with tracebacks)
- Standard notebook format (nbformat v4) — fully compatible with JupyterLab and VS Code

## Compatibility

| Requirement | Version |
|---|---|
| IntelliJ Platform | 2025.1+ (Community or Ultimate) |
| JVM | 21+ |
| Python | 3.8+ (with `ipykernel` installed) |

The plugin works with any IntelliJ-based IDE that ships JCEF (JetBrains Chromium Embedded Framework): IntelliJ IDEA, PyCharm, WebStorm, GoLand, CLion, etc.

## Installation

Download the latest release ZIP from the [Releases](../../releases) page, then:

**Settings → Plugins → Gear icon → Install Plugin from Disk…** → select the ZIP file.

Or place the extracted JAR into your IDE's `plugins/jupyter-openide/lib/` directory and restart.

## Usage

1. Open any `.ipynb` file — the notebook editor activates automatically.
2. Single-click a cell to select it; double-click its source to edit. Press **Shift+Enter** to execute and advance.
3. The kernel starts on first execution using the project's Python SDK.
4. To attach instead, choose **Connect / Start Kernel** and provide a Jupyter connection file or paste its connection JSON.

## Keyboard Shortcuts

| Shortcut | Action |
|---|---|
| Shift+Enter / Cmd+Enter | Execute cell and move to next |
| Cmd/Ctrl+Click or Cmd/Ctrl+B | Go to declaration/source |
| Escape | Exit cell edit mode |
| Cmd+S | Save notebook |
| Tab | Indent (in code cell) |

## Building from Source

```bash
./gradlew buildPlugin
```

The distributable ZIP will be placed in `dist/`.

## Contributing

Contributions are welcome — bug reports, feature requests, and pull requests all help.

- **Issues** — open an [issue](../../issues) for bugs or feature ideas. Please include your IDE name & version, OS, Python version, and steps to reproduce.
- **Pull requests** — fork the repository, create a feature branch off `main`, and open a PR back to `main`:

  ```bash
  git checkout -b my-feature
  # …make your changes…
  ./gradlew buildPlugin   # make sure it builds
  ```

- **Code style** — Kotlin follows standard conventions (JVM 21). Keep the front-end (`src/main/resources/notebook/notebook.js`) framework-free vanilla JavaScript — no external libraries.
- **Review & merge** — `main` is a protected branch: all changes land through pull requests, and only the maintainer ([@germanKoch](https://github.com/germanKoch)) merges into `main`. Please don't push to `main` directly.

By contributing, you agree that your contributions will be licensed under the project's [MIT License](LICENSE).

See [CHANGELOG.md](CHANGELOG.md) for release history and upgrade notes.

## License

[MIT](LICENSE) — Copyright (c) 2026 German Kochnev
