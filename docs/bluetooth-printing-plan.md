# Bluetooth Printing Architecture Plan

This document defines the Android Bluetooth printing boundary for FW-ERP PDA.
The first implementation step is a native diagnostic bridge for paired printer
connection testing only. It does not implement STORE_ITEM production printing,
FW-ERP API clients, backend status writeback, or web print-job completion.

## Current State

The Android app is a WebView shell. FW-ERP `/app/` owns the UI, business rules,
barcode rules, and print job creation.

Current production printing flow:

1. The operator uses FW-ERP inside the Android WebView.
2. FW-ERP creates the print job and print payload.
3. Android does not generate business barcodes.
4. Android does not call FW-ERP APIs directly.
5. Android does not mark any web print job as printed.

The current shell must continue to treat FW-ERP backend/web as the source of
truth for print payloads and barcode values.

## Diagnostic Bridge

The Clerk PDA diagnostic bridge is exposed to trusted FW-ERP WebView pages as:

```text
window.DirectLoopPdaPrinter
```

The web UI placement is:

```text
Clerk PDA -> 我的 -> 蓝牙打印机测试
Clerk PDA -> 我的 -> 打印机连接
```

Supported methods:

- `getPrinterStatus()`
- `listPairedPrinters()`
- `startPrinterDiscovery()`
- `stopPrinterDiscovery()`
- `getDiscoveredPrinters()`
- `connectPrinter(configOrAddress)`
- `disconnectPrinter()`
- `printTestLabel(protocol)`
- `getLastPrintResult()`

`getPrinterStatus()` includes paired printer fields plus discovery fields:
`discovery_status`, `discovered_printer_count`, and `discovered_printers`.
Discovery is diagnostic-only, uses Android Bluetooth Classic discovery, includes
paired printers and nearby discovered devices, and deduplicates by Bluetooth
address.

`connectPrinter` accepts a paired Bluetooth MAC address string or a JSON string
with:

```json
{
  "profile": "CHITENG_S1",
  "address": "00:11:22:33:44:55",
  "name": "Chiteng S1"
}
```

Supported profiles:

- `CHITENG_S1`
- `UROVO`
- `GENERIC`

Supported diagnostic protocols:

- `TSPL`
- `CPCL`
- `ESC_POS`

Each test label includes `DIRECT LOOP`, `PRINTER TEST`,
`MODEL: <selected profile>`, `TEST123456`, and a timestamp. The diagnostic
bridge uses Bluetooth Classic SPP first with UUID
`00001101-0000-1000-8000-00805F9B34FB`.

Trusted-host rule:

- Bridge methods only operate when the WebView is on configured `FW_ERP_HOST`.
- Untrusted pages receive a rejected JSON status.
- External URLs are still opened outside the PDA shell.

## Future Direction

Future Android work may add production Bluetooth printing after the diagnostic
bridge confirms which protocol works for Chiteng S1 and Urovo Bluetooth label
printers.

Expected production label sizes:

- 60x40
- 40x30

The future target flow is:

1. FW-ERP backend/web creates the print payload.
2. Android receives or syncs the backend/web print payload.
3. Android sends the provided payload to a paired Bluetooth label printer.
4. Android reports the print result back to FW-ERP.

Android should be a transport layer for backend/web print payloads, not a second
business barcode generator.

## Proposed Modules

### PrinterDiscovery

Responsible for locating candidate Bluetooth printers when a future discovery
flow exists. The current diagnostic bridge can search nearby Bluetooth devices,
but operators still pair Chiteng S1 and Urovo printers in Android Bluetooth
settings before connection.

Responsibilities:

- List supported paired or discoverable printers.
- Identify paired label-printer devices and their selected profile.
- Return device metadata needed by the connection layer.

Out of scope for the diagnostic bridge:

- User-facing pairing UI.

### PrinterConnection

Responsible for managing the Bluetooth printer session.

Responsibilities:

- Open and close a printer connection.
- Expose connection state to the print client.
- Surface connection errors in a reportable form.

Out of scope for the diagnostic bridge:

- Reconnect policy.
- Production retry policy.

### Protocol Test Clients

Responsible for sending diagnostic TSPL, CPCL, and ESC/POS test labels to the
connected printer.

Responsibilities:

- Build a small diagnostic label.
- Send it through `PrinterConnection`.
- Record the last tested protocol and result.

Out of scope for the diagnostic bridge:

- Barcode rendering or barcode value generation.
- STORE_ITEM payload generation.
- Web print-job status changes.

### PrintJobSync

Responsible for moving FW-ERP print jobs from backend/web ownership into the
future Android printing transport.

Responsibilities:

- Receive or fetch print jobs created by FW-ERP.
- Preserve print payload fields exactly as provided.
- Queue jobs only after the backend/web print payload exists.

Out of scope for this PR:

- API client implementation.
- Offline queue implementation.
- Print job persistence.

### PrintResultReporter

Responsible for reporting future Android-side print outcomes back to FW-ERP.

Responsibilities:

- Report success, failure, cancellation, and retryable printer states.
- Preserve the FW-ERP print job identity.
- Avoid changing business state without backend confirmation.

Out of scope for this PR:

- API client implementation.
- Backend status schema changes.
- Retry scheduler implementation.

## Barcode and Business Boundaries

Future Android Bluetooth printing must preserve these rules:

- Do not change barcode rules.
- Do not change `STORE_ITEM`.
- Do not change POS guardrails.
- Android must not regenerate business barcodes.
- Android must send only the print payload provided by FW-ERP backend/web.
- Android must not infer barcode values from UI text, label size, printer model,
  or local cached state.
- Android must not create alternate barcode formats for `60x40` or `40x30`
  labels.

The backend/web print payload is the contract. Android can validate that a
payload exists and can transport it to the printer, but Android cannot reinterpret
business barcode identity.

## Explicit Non-Goals

This plan does not implement:

- STORE_ITEM production batch printing.
- Marking any web print job as printed.
- FW-ERP backend status writeback from Android.
- Device discovery UI.
- FW-ERP API client.
- Local barcode generation.
- Changes to WebView shell behavior.
- Changes to FW-ERP backend, barcode, `STORE_ITEM`, or POS guardrail behavior.

## PR Boundary

The diagnostic bridge PR review surface is:

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/directloop/pda/MainActivity.kt`
- `app/src/main/java/com/directloop/pda/DirectLoopPdaPrinterBridge.kt`
- `app/src/main/java/com/directloop/pda/BluetoothPrinterTestPayloads.kt`
- `README.md`
- `docs/bluetooth-printing-plan.md`
- `scripts/validate_webview_shell.sh`

Production printing PRs should be split later by module and must keep the
backend/web print payload as the source of truth.
