# Direct Loop PDA

Direct Loop PDA is the first Android WebView shell for FW-ERP.

The Android app loads the existing FW-ERP `/app/`:

```text
https://fw-erp-34-35-52-250.nip.io/app/
```

The FW-ERP endpoint URL and host are configured through Android `BuildConfig`
fields in `app/build.gradle.kts`. Future production or staging domain changes
should update those BuildConfig values instead of changing app logic.

## Scope

- This Android app is a WebView shell.
- UI and APIs are served by FW-ERP.
- #195 PDA UI is reused through /app.
- Data remains in the FW-ERP backend.
- Android does not duplicate ERP business logic.
- Android does not call FW-ERP APIs directly yet.
- Native scanner, Bluetooth printing, and offline queue will be separate future PRs.
- Bluetooth printing is currently architecture planning only; see
  [docs/bluetooth-printing-plan.md](docs/bluetooth-printing-plan.md).

## Current behavior

- App name: `Direct Loop PDA`.
- Opens FW-ERP `/app/` in a portrait-only Android WebView.
- Enables JavaScript, DOM storage, localStorage/sessionStorage, cookies, and WebView persistence needed for login.
- Keeps FW-ERP role landing inside the web app:
  - `store_clerk` can reach the PDA clerk flow, including #195 PDA 现场分堆标价 UI.
  - `store_manager` can reach the manager flow when available.
  - `admin` can preview store PDA pages.
- Supports Android back:
  - WebView `goBack()` when history exists.
  - Normal app exit when there is no WebView history.
- Provides a network/offline error view with a retry button.
- Requests camera permission only for WebView camera/file chooser use.
- Supports WebView file chooser with image selection and camera capture.
- PDA scanner input mode: after each WebView page load, the shell asks the page
  to focus the most likely scan input when no FW-ERP text input is already
  focused on an equal-or-higher-priority input. Candidate inputs are matched
  first by `data-scan-input="true"`, then by `barcode`, `machine_code`, or
  `scan` metadata, and only then by search input metadata.
- Hardware Enter from a PDA scan head or Bluetooth scanner is left to the
  focused FW-ERP input, so existing web submit/search behavior still owns the
  action.

## PDA scanner input mode

Direct Loop PDA v0.1 treats scanner hardware as keyboard input. The supported
devices for this mode are:

- PDA scan heads that type the barcode into the currently focused input and send
  Enter.
- Bluetooth scanners configured as keyboard/HID devices that type the barcode
  into the currently focused input and send Enter.

The Android shell does not add native scanner SDK handling. No native scanner SDK
or CameraX flow is used for scanning in this PR. FW-ERP pages remain responsible
for validation, submit, routing, and any barcode-specific business behavior.

The shell injects a lightweight focus helper after a page finishes loading and
after the WebView loading/offline handling has run. The helper only attempts to
focus visible, editable text inputs that look like scan targets by explicit
`data-scan-input="true"` marker, `id`, `name`, placeholder, ARIA label, test id,
class, or input type. A generic search input is only the last fallback, so it is
not chosen over a real scan input.

## Non-goals

- No native rebuild of #195 screens.
- No copied FW-ERP frontend files.
- No duplicate Android API clients.
- No Bluetooth permission flow, socket connection, device discovery, or TSPL sending.
- No backend code.
- No Zebra SDK, Honeywell SDK, Urovo SDK, CameraX scanner flow, POS logic,
  printing, or offline queue.
- No hardcoded secrets.
- No APK/build outputs committed.

## Bluetooth Printing Roadmap

Future Android Bluetooth printing will keep FW-ERP backend/web as the source of
truth for print jobs and barcode payloads. Android may later connect directly to
Deli DL-720C label printers for `60x40` and `40x30` labels, but it must only
transport print payloads created by FW-ERP.

Planned future module boundaries are documented in
[docs/bluetooth-printing-plan.md](docs/bluetooth-printing-plan.md):

- `PrinterDiscovery`
- `PrinterConnection`
- `TsplPrinterClient`
- `PrintJobSync`
- `PrintResultReporter`

Bluetooth printing must not change barcode rules, `STORE_ITEM`, POS guardrails,
or WebView shell behavior. Android must not regenerate business barcodes.

## Build

Open the repo in Android Studio and sync Gradle.

The project is a Kotlin Android project with package:

```text
com.directloop.pda
```

Local files such as `local.properties`, `.gradle/`, `build/`, APKs, and AABs are ignored and should not be committed.

## Validation

Run the repository contract check:

```bash
bash scripts/validate_webview_shell.sh
```

If Android SDK and Gradle are installed, also run a normal Android build from Android Studio or Gradle.

Manual PDA scanner-mode test:

1. Install or run the app on a PDA/WebView device.
2. Open an FW-ERP `/app/` page that has `data-scan-input="true"`, `barcode`,
   `machine_code`, `scan`, or search input metadata.
3. Confirm the cursor lands in the expected scan input after the page loads, or
   that FW-ERP's own already focused text input remains focused.
4. Scan with a PDA scan head or Bluetooth keyboard-mode scanner.
5. Confirm the barcode text appears in the focused web input and hardware Enter
   triggers the existing FW-ERP web submit/search behavior.
