# Jupyter Notebook Plugin for IntelliJ

Open, edit, and execute `.ipynb` notebooks directly inside IntelliJ-based IDEs. The plugin uses the project's Python environment to launch an IPython kernel and communicates with it over ZeroMQ — no external Jupyter server required.

## Features

- Native notebook editor with code and markdown cells
- Python syntax highlighting with cross-cell variable awareness
- Cell execution via Shift+Enter / Cmd+Enter
- Automatic kernel lifecycle management (start, stop, restart, interrupt)
- Auto-detection of the project's Python interpreter and `ipykernel`
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
2. Click a cell to edit. Press **Shift+Enter** to execute and advance.
3. The kernel starts on first execution using the project's Python SDK.

## Keyboard Shortcuts

| Shortcut | Action |
|---|---|
| Shift+Enter / Cmd+Enter | Execute cell and move to next |
| Escape | Exit cell edit mode |
| Cmd+S | Save notebook |
| Tab | Indent (in code cell) |

## Building from Source

```bash
./gradlew buildPlugin
```

The distributable ZIP will be placed in `dist/`.

## License

[MIT](LICENSE) — Copyright (c) 2026 German Kochnev
