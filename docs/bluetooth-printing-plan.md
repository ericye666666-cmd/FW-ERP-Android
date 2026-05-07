# Bluetooth Printing Architecture Plan

This document defines the future Android Bluetooth printing boundary for FW-ERP
PDA. It is an architecture plan only. This PR does not implement Bluetooth
permissions, device discovery, socket connections, TSPL sending, or FW-ERP API
clients.

## Current State

The Android app is a WebView shell. FW-ERP `/app/` owns the UI, business rules,
barcode rules, and print job creation.

Current printing flow:

1. The operator uses FW-ERP inside the Android WebView.
2. FW-ERP creates the print job and print payload.
3. Android does not generate business barcodes.
4. Android does not call FW-ERP APIs directly.
5. Android does not connect to a label printer.

The current shell must continue to treat FW-ERP backend/web as the source of
truth for print payloads and barcode values.

## Future Direction

Future Android work may add direct Bluetooth printing for label printers such
as:

- Deli DL-720C

Expected label sizes:

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

Responsible for locating candidate Bluetooth printers when a future permission
and discovery flow exists.

Responsibilities:

- List supported paired or discoverable printers.
- Identify the target device class for Deli DL-720C style label printers.
- Return device metadata needed by the connection layer.

Out of scope for this PR:

- Android Bluetooth permission flow.
- Device discovery implementation.
- User-facing pairing UI.

### PrinterConnection

Responsible for managing a future Bluetooth printer session.

Responsibilities:

- Open and close a printer connection.
- Expose connection state to the print client.
- Surface connection errors in a reportable form.

Out of scope for this PR:

- Bluetooth socket implementation.
- Reconnect policy.
- Low-level byte stream handling.

### TsplPrinterClient

Responsible for sending backend/web-provided TSPL payloads to the connected
printer.

Responsibilities:

- Accept a print payload prepared by FW-ERP backend/web.
- Send the payload through `PrinterConnection`.
- Keep label-size handling aligned to backend/web payload metadata.

Out of scope for this PR:

- TSPL command generation.
- TSPL byte sending.
- Barcode rendering or barcode value generation.

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

- Bluetooth permission flow.
- Bluetooth socket connection.
- Device discovery.
- TSPL sending.
- FW-ERP API client.
- Local barcode generation.
- Changes to WebView shell behavior.
- Changes to FW-ERP backend, barcode, `STORE_ITEM`, or POS guardrail behavior.

## PR Boundary

This PR is documentation only. The intended review surface is:

- `docs/bluetooth-printing-plan.md`
- `README.md`

Implementation PRs should be split later by module and should keep the
backend/web print payload as the source of truth.
