# Jupyter Wire Protocol Messages Contract

**Date**: 2026-05-01  
**Type**: ZeroMQ message protocol (Jupyter messaging spec v5.4)

## Connection

The plugin connects to 5 ZeroMQ sockets after reading the kernel's connection file:

| Socket | Type | Purpose |
|--------|------|---------|
| Shell | DEALER | Execute requests, kernel info |
| IOPub | SUB | Broadcast outputs, status changes |
| Stdin | DEALER | Input requests from kernel (not used in v1) |
| Control | DEALER | Interrupt, shutdown commands |
| Heartbeat | REQ | Kernel liveness detection |

All sockets connect to `tcp://127.0.0.1:{port}` with ports from the connection file.

## Message Envelope

Every message is a multipart ZeroMQ frame sequence:

```
[routing_id]        (DEALER sockets prepend identity)
b"<IDS|MSG>"        (delimiter)
HMAC-SHA256(key, header+parent+metadata+content)
header              (JSON)
parent_header       (JSON)
metadata            (JSON)
content             (JSON)
[extra_buffers]     (optional binary data)
```

## Messages Sent by Plugin

### execute_request (Shell)

```json
{
  "header": { "msg_type": "execute_request", "msg_id": "<uuid>", "session": "<session_id>" },
  "content": {
    "code": "<cell source code>",
    "silent": false,
    "store_history": true,
    "allow_stdin": false,
    "stop_on_error": true
  }
}
```

### interrupt_request (Control)

```json
{
  "header": { "msg_type": "interrupt_request" },
  "content": {}
}
```

### shutdown_request (Control)

```json
{
  "header": { "msg_type": "shutdown_request" },
  "content": { "restart": false }
}
```

### kernel_info_request (Shell)

```json
{
  "header": { "msg_type": "kernel_info_request" },
  "content": {}
}
```

## Messages Received by Plugin

### status (IOPub)

```json
{
  "content": { "execution_state": "idle" | "busy" | "starting" }
}
```

Maps to `KernelStatus` enum. Updates the status indicator in the editor.

### stream (IOPub)

```json
{
  "content": { "name": "stdout" | "stderr", "text": "<output text>" }
}
```

Appended to the executing cell's output area.

### execute_result (IOPub)

```json
{
  "content": {
    "execution_count": 5,
    "data": { "text/plain": "42", "text/html": "<b>42</b>" },
    "metadata": {}
  }
}
```

Rendered as cell output. Plugin selects the richest supported MIME type: `text/html` > `image/png` > `image/svg+xml` > `text/plain`.

### display_data (IOPub)

```json
{
  "content": {
    "data": { "image/png": "<base64>", "text/plain": "<fallback>" },
    "metadata": {}
  }
}
```

Same MIME type selection as `execute_result`.

### error (IOPub)

```json
{
  "content": {
    "ename": "NameError",
    "evalue": "name 'x' is not defined",
    "traceback": ["<ANSI-colored traceback lines>"]
  }
}
```

Rendered with ANSI color codes stripped or converted to HTML.

### execute_reply (Shell)

```json
{
  "content": {
    "status": "ok" | "error" | "abort",
    "execution_count": 5
  }
}
```

Updates the cell's execution count display.
