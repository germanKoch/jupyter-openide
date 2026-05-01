# Plugin Extension Points Contract

**Date**: 2026-05-01  
**Type**: IntelliJ Platform plugin.xml declarations

## File Type Registration

The plugin registers `.ipynb` as a recognized file type:

```xml
<extensions defaultExtensionNs="com.intellij">
    <fileType 
        name="Jupyter Notebook" 
        implementationClass="...JupyterNotebookFileType"
        fieldName="INSTANCE"
        language="JSON"
        extensions="ipynb"/>
</extensions>
```

## Custom Editor Provider

The plugin provides a custom editor for `.ipynb` files:

```xml
<extensions defaultExtensionNs="com.intellij">
    <fileEditorProvider 
        implementation="...JupyterNotebookEditorProvider"/>
</extensions>
```

**Contract**:
- `accept(project, file)`: Returns `true` for files with `.ipynb` extension
- `createEditor(project, file)`: Returns `JupyterNotebookEditor` instance
- Policy: `HIDE_DEFAULT_EDITOR` — replaces default JSON text editor

## Actions

The plugin registers these user-facing actions:

| Action ID | Text | Placement | Description |
|-----------|------|-----------|-------------|
| `jupyter.startKernel` | Start Kernel | Editor toolbar | Starts kernel for current notebook |
| `jupyter.stopKernel` | Stop Kernel | Editor toolbar | Stops the running kernel |
| `jupyter.restartKernel` | Restart Kernel | Editor toolbar | Restarts the kernel |
| `jupyter.interruptKernel` | Interrupt | Editor toolbar | Interrupts current execution |
| `jupyter.runCell` | Run Cell | Editor toolbar + gutter | Executes the current cell |
| `jupyter.runAllCells` | Run All | Editor toolbar | Executes all cells sequentially |
| `jupyter.addCodeCell` | Add Code Cell | Editor toolbar | Inserts a new code cell |
| `jupyter.addMarkdownCell` | Add Markdown Cell | Editor toolbar | Inserts a new markdown cell |
| `jupyter.deleteCell` | Delete Cell | Cell context menu | Deletes the selected cell |

## Plugin Dependencies

```xml
<depends>com.intellij.modules.platform</depends>
<depends optional="true" config-file="python-support.xml">com.intellij.modules.python</depends>
```

The Python plugin dependency is optional — the notebook viewer works without it, but kernel start requires a configured Python interpreter.
