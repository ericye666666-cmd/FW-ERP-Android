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
- Native scanner and offline queue will be separate future PRs.
- Bluetooth printer production printing remains future work; #19 only verifies a
  one-label STORE_ITEM preview bridge.
- Bluetooth printer batch production printing remains future work; this app has
  a Clerk PDA diagnostic bridge for paired-printer connection/test labels and a
  one-label STORE_ITEM preview bridge for Chiteng S1 verification.
- Bluetooth printing architecture notes are in
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

## Bluetooth printer diagnostic bridge

Clerk PDA can expose this native diagnostic surface in the FW-ERP web UI under
`Clerk PDA -> 我的 -> 蓝牙打印机测试` and the printer connection flow under
`Clerk PDA -> 我的 -> 打印机连接`.

The Android WebView provides:

```text
window.DirectLoopPdaPrinter
```

The bridge is only allowed while the loaded WebView page is on the configured
`FW_ERP_HOST`. External websites are opened outside the shell and bridge calls
from an untrusted page return a rejected status instead of touching Bluetooth.

Supported bridge methods:

- `getPrinterStatus()`
- `listPairedPrinters()`
- `startPrinterDiscovery()`
- `stopPrinterDiscovery()`
- `getDiscoveredPrinters()`
- `connectPrinter(configOrAddress)`
- `disconnectPrinter()`
- `printTestLabel(protocol)`
- `printStoreItemLabelPreview(payloadJson)`
- `getLastPrintResult()`

`getPrinterStatus()` also reports `discovery_status`,
`discovered_printer_count`, `discovered_printers`, `printer_online_status`,
`printer_health_checked_at`, and official Chiteng SDK health fields. Discovery
uses Android Bluetooth Classic search, includes already paired printers,
deduplicates by Bluetooth address, and times out after a short scan window.
Unpaired discovered printers must be paired in Android system Bluetooth before
`connectPrinter` can connect.

Paired and discovered devices are not treated as live printers. A paired device
only means Android knows the bond; a discovered device only means Bluetooth saw
it during scanning. Printer list rows include `source` (`paired` or
`discovered`) and `online_status`, which remains `unknown` unless a separate
connection health check verifies the selected printer.

`connectPrinter` accepts either a paired Bluetooth MAC address string or a JSON
string with the selected test profile:

```json
{
  "profile": "CHITENG_S1",
  "address": "00:11:22:33:44:55",
  "name": "Chiteng S1"
}
```

Supported profiles:

- `CHITENG_S1`
- `CHITENG_S1_OFFICIAL`
- `UROVO`
- `GENERIC`

Supported test protocols:

- `TSPL`
- `TSPL_SIMPLE_TEXT`
- `TSPL_DENSITY_TEXT`
- `TSPL_NO_GAP_CONTINUOUS`
- `TSPL_GAP_DETECT`
- `RAW_LF_FEED`
- `CPCL`
- `CPCL_SIMPLE_TEXT`
- `ESC_POS`
- `ESC_POS_TEXT`
- `CHITENG_S1_OFFICIAL`

The original `TSPL`, `CPCL`, and `ESC_POS` labels include `DIRECT LOOP`,
`PRINTER TEST`, `MODEL: <selected profile>`, `TEST123456`, and a timestamp.
The Chiteng S1 variants intentionally use simpler payloads for diagnosis. This
does not read, update, or mark any FW-ERP web print job as printed.

`CHITENG_S1_OFFICIAL` uses the official Chiteng CTPL Android SDK from
`app/libs/ctaiotCtpl1.1.8.jar` to print one 60x40 diagnostic label with text and
a Code128 barcode for `TEST123456`. It is still diagnostic-only: Android does
not generate STORE_ITEM barcodes and does not mark any FW-ERP print job as
printed.

`printStoreItemLabelPreview(payloadJson)` is the first STORE_ITEM print bridge.
It prints exactly one STORE_ITEM preview label from a FW-ERP-provided payload,
supports 60x40 and 40x30 gap labels, and requires `machine_code` to be numeric
and start with `5`. Android only prints the barcode value supplied by FW-ERP:
it does not generate or transform STORE_ITEM barcodes. No batch printing is
allowed, no FW-ERP print job is marked printed, and no sticker confirmation is
written by Android.

For the Chiteng S1 preview print path, Android sends raw TSPL bytes over a
one-shot Bluetooth SPP socket instead of using CTPL bitmap drawing. The TSPL
uses only `SIZE`, `CLS`, `SPEED`, `DENSITY`, `DIRECTION`, `TEXT`, `BARCODE`,
and `PRINT 1,1`; every line ends with `\r\n` and the bytes are encoded as GBK.
If a second preview request arrives while the previous label is likely still
moving, the bridge returns `Printer is busy. Wait before printing again.`
instead of queueing another feed.

Required preview payload shape:

```json
{
  "printer_profile": "CHITENG_S1_OFFICIAL",
  "label_template_size": "60x40",
  "label_width_mm": 60,
  "label_height_mm": 40,
  "print_mode": "preview_one",
  "labels": [
    {
      "machine_code": "5261300000038",
      "barcode_value": "5261300000038",
      "price_kes": 410,
      "category_short": "CARGO PANT",
      "grade": "P"
    }
  ]
}
```

Contract summary:

- Adds `printStoreItemLabelPreview(payloadJson)`.
- Prints exactly one STORE_ITEM preview label.
- Supports 60x40 and 40x30 gap labels.
- Requires `machine_code` to be numeric and start with `5`.
- Android only prints FW-ERP-provided barcode payload.
- Sends raw TSPL over Bluetooth SPP for the Chiteng S1 preview path.
- No batch printing.
- No print job is marked printed.
- No sticker confirmation.
- No barcode generation in Android.

For `CHITENG_S1_OFFICIAL`, status polling does not rely on a previous connect
success alone. The bridge uses the CTPL `queryPrintState()` response as a
non-printing health check; if the printer does not respond, the selected printer
is reported as disconnected/offline instead of staying connected forever. The
health check never feeds paper and never marks a business print job as printed.

Chiteng S1 diagnostic note: TSPL feeds paper but prints no content. Because the
socket write succeeds, the suspected cause is a media/gap/font/payload mismatch.
Use the `TSPL_SIMPLE_TEXT`,
`TSPL_DENSITY_TEXT`, `TSPL_NO_GAP_CONTINUOUS`, `TSPL_GAP_DETECT`,
`RAW_LF_FEED`, `ESC_POS_TEXT`, and `CPCL_SIMPLE_TEXT` variants to isolate the
correct command mode before any production STORE_ITEM printing is implemented.

Bridge status responses include:

```json
{
  "bridge_available": true,
  "bluetooth_enabled": true,
  "paired_printer_count": 0,
  "paired_printers": [],
  "discovery_status": "idle",
  "discovered_printer_count": 0,
  "discovered_printers": [],
  "selected_printer_name": "",
  "selected_printer_address": "",
  "selected_profile": "GENERIC",
  "connection_status": "disconnected",
  "printer_online_status": "unknown",
  "printer_health_checked_at": "",
  "official_sdk_available": true,
  "official_sdk_connected": false,
  "official_sdk_last_message": "",
  "official_sdk_last_error": "",
  "last_error": "",
  "last_protocol_tested": "",
  "last_print_result": "none"
}
```

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
- No STORE_ITEM production batch printing.
- No STORE_ITEM batch bridge; only one-label preview printing is exposed.
- No marking web print jobs as printed.
- No FW-ERP backend status writeback from Android.
- No Android-side pairing UI; unpaired discovered printers still need Android
  system Bluetooth pairing before connection.
- No backend code.
- No Zebra SDK, Honeywell SDK, Urovo SDK, CameraX scanner flow, POS logic,
  production printing, or offline queue.
- No hardcoded secrets.
- No APK/build outputs committed.

## Bluetooth Printing Roadmap

The current Bluetooth implementation is a Clerk PDA diagnostic bridge for paired
printer connection and protocol testing. It targets Chiteng S1, Urovo Bluetooth
label printers, and a generic SPP profile. It uses Bluetooth Classic SPP with
UUID `00001101-0000-1000-8000-00805F9B34FB`.

Future Android Bluetooth production printing will keep FW-ERP backend/web as
the source of truth for print jobs and barcode payloads. Android may later
transport FW-ERP-created `60x40` and `40x30` payloads to a paired label printer,
but it must not generate business barcodes locally.

Planned future module boundaries are documented in
[docs/bluetooth-printing-plan.md](docs/bluetooth-printing-plan.md):

- `PrinterDiscovery` or paired-device selection
- `PrinterConnection`
- protocol-specific print clients
- `PrintJobSync`
- `PrintResultReporter`

Bluetooth diagnostics and future production printing must not change barcode
rules, `STORE_ITEM`, POS guardrails, or WebView shell behavior. Android must not
regenerate business barcodes.

## Build

Open the repo in Android Studio and sync Gradle.

The project is a Kotlin Android project with package:

```text
com.directloop.pda
```

Local files such as `local.properties`, `.gradle/`, `build/`, APKs, and AABs are ignored and should not be committed.

## Download Debug APK from GitHub Actions

The debug APK is for internal staging PDA testing. It still loads the configured
FW-ERP staging app:

```text
https://fw-erp-34-35-52-250.nip.io/app/
```

Non-developer install steps:

1. Open the GitHub repository for `FW-ERP-Android`.
2. Click the `Actions` tab.
3. Open the latest `Build Debug APK` run.
4. Download the `direct-loop-pda-debug-apk` artifact.
5. Unzip the download if GitHub saves it as a `.zip` file.
6. Find `direct-loop-pda-debug.apk`.
7. Transfer the APK to the PDA or Android phone.
8. Install the APK on the device.
9. If Android blocks installation, allow install from unknown sources for the
   app you used to open the APK.
10. Open `Direct Loop PDA`.
11. Confirm the app loads `https://fw-erp-34-35-52-250.nip.io/app/`.

## PDA login session debug test

Use this check when validating a new internal debug APK on a real PDA:

1. Clear Direct Loop PDA app storage once after installing the new APK.
2. Open Direct Loop PDA and confirm the API base shown by FW-ERP is the staging
   API.
3. Log in as `Austin / demo1234`.
4. Confirm the app stays in `店员 PDA 工作台` and does not return to the login
   page.
5. Close and reopen Direct Loop PDA.
6. Confirm the session is still present when WebView storage persists, or at
   minimum that Android did not clear the FW-ERP localStorage keys unexpectedly.

In debug builds, Android Logcat includes a `DirectLoopPDA` storage probe after
page load. It reports only whether these localStorage keys exist, not their
values:

- `retail_ops_access_token`
- `retail_ops_current_user`
- `retail_ops_api_base`

## Stable internal debug signing

GitHub Actions debug APKs should use one stable internal signing key. Android
checks the signing certificate when installing an APK over an existing app with
the same package name, so a differently signed debug APK can be blocked as a
signature conflict. Stable signing lets internal testers install future
`direct-loop-pda-debug.apk` artifacts as updates over the previous Direct Loop
PDA debug APK.

Configure these GitHub repository secrets before relying on update installs:

- `PDA_DEBUG_KEYSTORE_BASE64`: base64-encoded debug keystore file.
- `PDA_DEBUG_KEYSTORE_PASSWORD`: keystore password.
- `PDA_DEBUG_KEY_ALIAS`: key alias inside the keystore.
- `PDA_DEBUG_KEY_PASSWORD`: key password.

Do not commit the `.jks` / `.keystore` file or passwords. Keep the original
keystore in a secure internal password manager or vault so the same signing key
can be reused for future GitHub Actions builds.

Rotate the key only when the signing key is lost or compromised. To rotate it:

1. Create a replacement internal debug keystore.
2. Base64-encode the keystore file.
3. Replace all four GitHub Secrets together.
4. Tell testers that Android will treat the rotated-key APK as differently
   signed.

If a PDA or Android phone already has a differently signed debug APK installed,
uninstall once, then install the stable-signed APK from GitHub Actions. Future
APK artifacts signed with the same stable key should update normally without
uninstalling first.

## Validation

Run the repository contract check:

```bash
bash scripts/validate_webview_shell.sh
```

If Android SDK and Gradle are installed, also run:

```bash
./gradlew assembleDebug
```

Manual PDA scanner-mode test:

1. Install or run the app on a PDA/WebView device.
2. Open an FW-ERP `/app/` page that has `data-scan-input="true"`, `barcode`,
   `machine_code`, `scan`, or search input metadata.
3. Confirm the cursor lands in the expected scan input after the page loads, or
   that FW-ERP's own already focused text input remains focused.
4. Scan with a PDA scan head or Bluetooth keyboard-mode scanner.
5. Confirm the barcode text appears in the focused web input and hardware Enter
   triggers the existing FW-ERP web submit/search behavior.

Manual Bluetooth printer diagnostic test:
1. Pair Chiteng S1 in Android Bluetooth settings.
2. Pair the Urovo Bluetooth printer in Android Bluetooth settings.
3. Open `Direct Loop PDA`.
4. In FW-ERP, open `Clerk PDA -> 我的 -> 蓝牙打印机测试` or
   `Clerk PDA -> 我的 -> 打印机连接` using `window.DirectLoopPdaPrinter`.
5. Confirm `listPairedPrinters()` returns paired devices.
6. Run `startPrinterDiscovery()` and confirm `getDiscoveredPrinters()` includes
   paired printers plus any nearby discovered devices.
7. Select `CHITENG_S1`, connect, then test `CHITENG_S1_OFFICIAL`, `TSPL`, `TSPL_SIMPLE_TEXT`, `TSPL_DENSITY_TEXT`, `TSPL_NO_GAP_CONTINUOUS`, `TSPL_GAP_DETECT`, `RAW_LF_FEED`, `ESC_POS_TEXT`, `CPCL_SIMPLE_TEXT`, `CPCL`, and `ESC_POS`.
8. Select `UROVO`, connect, then test `TSPL`, `CPCL`, and `ESC_POS`.
9. Record which protocol prints correctly for each model.
