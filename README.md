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

- `getAppInfo()`
- `getPrinterStatus()`
- `listPairedPrinters()`
- `startPrinterDiscovery()`
- `stopPrinterDiscovery()`
- `getDiscoveredPrinters()`
- `connectPrinter(configOrAddress)`
- `disconnectPrinter()`
- `printTestLabel(protocol)`
- `printStoreItemLabelPreview(payloadJson)`
- `printStoreItemLabelPreviewCtplNoLabelMode(payloadJson)`
- `printStoreItemLabelPreviewCtplBitmapDemo(payloadJson)`
- `printStoreItemLabelPreviewRawTspl(payloadJson)`
- `printS1RawTsplMinText()`
- `printS1RawTsplBlackBox()`
- `getUrovoPrinterStatus()`
- `printUrovoK300MinText()`
- `printUrovoK300BlackBox()`
- `printUrovoK300StoreItemPreview(payloadJson)`
- `printK300EscposMinText()`
- `printK300CpclMinText()`
- `printK300TsplMinText()`
- `printK300TsplBlackBox()`
- `testK300SppConnection()`
- `getLastPrintResult()`

`getAppInfo()` is the non-printing bridge version probe used by FW-ERP login
and diagnostics pages. It is only available on the trusted `FW_ERP_HOST` page
and does not expose secrets, printer credentials, or business data:

```json
{
  "app_name": "Direct Loop PDA",
  "version_name": "0.1.0",
  "version_code": 1,
  "package_name": "com.directloop.pda",
  "bridge_version": "pda-android-20260510-appinfo",
  "supported_methods": [
    "getPrinterStatus",
    "connectPrinter",
    "disconnectPrinter",
    "printTestLabel",
    "printStoreItemLabelPreview",
    "printStoreItemLabelPreviewCtplNoLabelMode",
    "printStoreItemLabelPreviewCtplBitmapDemo",
    "printStoreItemLabelPreviewRawTspl",
    "printS1RawTsplMinText",
    "printS1RawTsplBlackBox",
    "getUrovoPrinterStatus",
    "printUrovoK300MinText",
    "printUrovoK300BlackBox",
    "printUrovoK300StoreItemPreview",
    "printK300EscposMinText",
    "printK300CpclMinText",
    "printK300TsplMinText",
    "printK300TsplBlackBox",
    "testK300SppConnection"
  ]
}
```

`getPrinterStatus()` also reports `discovery_status`,
`discovered_printer_count`, `discovered_printers`, `printer_online_status`,
`printer_health_checked_at`, official Chiteng SDK health fields, and Urovo K300
PrinterManager fields such as `urovo_printer_available`,
`urovo_last_status_code`, and `urovo_last_status_text`. External K300 Bluetooth
SPP diagnostics also report `k300_spp_available`,
`k300_spp_last_checked_at`, and `k300_spp_last_error`. Discovery uses Android
Bluetooth Classic search, includes already paired printers, deduplicates by
Bluetooth address, and times out after a short scan window. Unpaired discovered
printers must be paired in Android system Bluetooth before Bluetooth-based
profiles can connect.

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
- `UROVO_K300`
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

For Chiteng S1 preview diagnostics, Android exposes explicit one-label protocol
variants instead of hiding everything behind one misleading success result:

- `printStoreItemLabelPreviewCtplNoLabelMode(payloadJson)` uses the CTPL SDK
  with `clean`, `setSize`, `setPrintSpeed`, `setPrintDensity`, `drawText`,
  `drawBarCode`, `print(1)`, and `execute`. It does not call `Label_Divide`,
  `setPaperType`, or backpressure.
- `printStoreItemLabelPreviewCtplBitmapDemo(payloadJson)` uses the CTPL SDK
  single-bitmap demo sequence: `clean`, `setSize`, `drawBitmap(Rect(...))`,
  `print(1)`, and `execute`. It does not call `Label_Divide`, `setPaperType`,
  or backpressure.
- `printStoreItemLabelPreviewRawTspl(payloadJson)` sends raw TSPL over
  Bluetooth SPP using GBK bytes and CRLF line endings.
- `printS1RawTsplMinText()` sends the smallest 40x30 raw TSPL text probe:
  `SIZE 40 mm,30 mm`, `TEXT 100,150,"TSS24.BF2",0,1,1,"CHI TENG TSPL MANUAL"`,
  and `PRINT 1,1`.
- `printS1RawTsplBlackBox()` sends the smallest 40x30 raw TSPL graphic probe:
  `SIZE 40 mm,30 mm`, `CLS`, `SPEED 2`, `DENSITY 12`, `DIRECTION 0`,
  `BAR 20,20,200,100`, and `PRINT 1,1`.

The two minimal raw TSPL probes are diagnostic-only. They use one-shot Bluetooth
SPP writes, GBK encoding, and CRLF line endings. They do not send FEED,
FORMFEED, GAPDETECT, calibration, CTPL SDK drawing calls, `Label_Divide`, or
backpressure. Success only means the bytes were written and flushed to the
Bluetooth SPP socket; it does not prove the physical label printed.

The legacy `printStoreItemLabelPreview(payloadJson)` entry remains available for
older FW-ERP bundles, but it now routes to the CTPL no-label-mode variant rather
than the physically failed CTPL `Label_Divide` path.
If a second preview request arrives while the previous label is likely still
moving, the bridge returns `Printer is busy. Wait before printing again.`
instead of queueing another feed.

For Urovo K300 diagnostics, Android uses `android.device.PrinterManager`
through reflection so the debug APK still builds on non-Urovo CI machines. The
PrinterManager-specific diagnostic methods do not depend on a Bluetooth SPP
socket:

- `getUrovoPrinterStatus()` calls PrinterManager `open`, `getStatus`, and
  `close`, then updates `urovo_printer_available`, `urovo_last_status_code`,
  and `urovo_last_status_text`.
- `printUrovoK300MinText()` prints one 40x30 page using `setupPage(320,240)`,
  `clearPage`, two `drawText` calls (`DIRECT LOOP`, `K300 TEXT TEST`),
  `printPage(0)`, and `close`.
- `printUrovoK300BlackBox()` prints one 40x30 page with a rectangle and thick
  black diagonal using `drawLine`, then `printPage(0)`.
- `printUrovoK300StoreItemPreview(payloadJson)` prints exactly one 40x30
  STORE_ITEM preview label from FW-ERP payload data. It validates
  `printer_profile = UROVO_K300`, one label only, numeric `machine_code`
  starting with `5`, `barcode_value == machine_code`, and uses
  `drawBarcode` with Code128 type `20`.

Urovo status codes are surfaced as text: `PRNSTS_OK` -> `ok`,
`PRNSTS_OUT_OF_PAPER` -> `out_of_paper`, `PRNSTS_OVER_HEAT` -> `over_heat`,
`PRNSTS_UNDER_VOLTAGE` -> `under_voltage`, `PRNSTS_BUSY` -> `busy`,
`PRNSTS_ERR` -> `error`, and `PRNSTS_ERR_DRIVER` -> `driver_error`. Success from
these diagnostic methods only means the PrinterManager command returned without
throwing; it does not mark a physical label or FW-ERP print job as complete.

For external Urovo K300 printers that appear as paired Bluetooth Classic devices
but do not expose `android.device.PrinterManager`, Android also exposes
one-shot Bluetooth SPP protocol probes. These use the selected paired printer
address, cancel discovery when permission allows, open an RFCOMM socket with
UUID `00001101-0000-1000-8000-00805F9B34FB`, write bytes, flush, wait briefly,
and close the socket. They do not keep a socket open and do not claim physical
print success:

- `connectPrinter({"profile":"UROVO_K300", ...})` uses the external K300 SPP
  connection probe instead of PrinterManager. It saves the selected printer,
  opens a one-shot SPP socket, writes only ESC `@` bytes (`1B 40`), flushes,
  waits 300 ms, and closes the socket. It reports `K300_SPP_CONNECT_TEST`,
  `K300_BLUETOOTH_SPP`, `last_preview_tspl_bytes = 2`, and operations
  including `write_esc_init`. No visible print content is expected.
- `testK300SppConnection()` runs the same selected-printer SPP probe directly
  from diagnostics. On success it sets `connection_status = connected`,
  leaves `printer_online_status = unknown`, and sets
  `k300_spp_available = true`. On failure it sets `connection_status = error`
  and records `k300_spp_last_error`.
- `printK300EscposMinText()` sends ESC/POS bytes (`ESC @`, `K300 ESC/POS TEST`,
  `5261300000038`, and blank lines). It reports
  `K300_ESCPOS_MIN_TEXT`, `K300_BLUETOOTH_SPP`, an empty
  `last_preview_tspl_command`, and operations including
  `write_escpos_min_text`.
- `printK300CpclMinText()` sends CPCL with CRLF endings:
  `! 0 200 200 240 1`, two `TEXT` rows (`K300 CPCL TEST` and
  `5261300000038`), and `PRINT`.
- `printK300TsplMinText()` sends TSPL with CRLF endings:
  `SIZE 40 mm,30 mm`, `CLS`, two `TEXT` rows (`K300 TSPL TEST` and
  `5261300000038`), and `PRINT 1,1`.
- `printK300TsplBlackBox()` sends TSPL with CRLF endings:
  `SIZE 40 mm,30 mm`, `CLS`, `DENSITY 12`, `BAR 20,20,200,100`, and
  `PRINT 1,1`.

All K300 Bluetooth SPP probes use GBK-compatible bytes for text commands and
set `last_preview_tspl_bytes` to the actual byte count written. Success only
means bytes were written and flushed to Bluetooth SPP; it does not set
`printer_online_status = online`.

The latest `getPrinterStatus()` raw JSON includes preview-print diagnostics so
the FW-ERP PDA diagnostics panel can prove which preview path was used:
`last_protocol_tested` should be one of
`STORE_ITEM_LABEL_PREVIEW_CTPL_NO_LABEL_MODE`,
`STORE_ITEM_LABEL_PREVIEW_CTPL_BITMAP_DEMO`, or
`STORE_ITEM_LABEL_PREVIEW_TSPL`. Minimal raw probes use
`S1_RAW_TSPL_MIN_TEXT`, `S1_RAW_TSPL_BLACK_BOX`, `UROVO_K300_MIN_TEXT`,
`UROVO_K300_BLACK_BOX`, `UROVO_K300_STORE_ITEM_PREVIEW`,
`K300_ESCPOS_MIN_TEXT`, `K300_CPCL_MIN_TEXT`, `K300_TSPL_MIN_TEXT`, or
`K300_TSPL_BLACK_BOX`. The external K300 SPP connection probe reports
`K300_SPP_CONNECT_TEST`.
`last_preview_transport` should be one of `CTPL_SDK_NO_LABEL_MODE`,
`CTPL_SDK_BITMAP_DEMO`, `RAW_TSPL_SPP`, `UROVO_PRINTER_MANAGER`, or
`K300_BLUETOOTH_SPP`. `last_preview_sdk_operations` shows CTPL, Urovo
PrinterManager, or K300 SPP write operations, while raw command variants also
expose `last_preview_tspl_command`, `last_preview_tspl_lines`, and
`last_preview_tspl_bytes`.

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
- Adds `printStoreItemLabelPreviewCtplNoLabelMode(payloadJson)`.
- Adds `printStoreItemLabelPreviewCtplBitmapDemo(payloadJson)`.
- Adds `printStoreItemLabelPreviewRawTspl(payloadJson)`.
- Adds `printS1RawTsplMinText()`.
- Adds `printS1RawTsplBlackBox()`.
- Adds `getUrovoPrinterStatus()`.
- Adds `printUrovoK300MinText()`.
- Adds `printUrovoK300BlackBox()`.
- Adds `printUrovoK300StoreItemPreview(payloadJson)`.
- Adds `printK300EscposMinText()`.
- Adds `printK300CpclMinText()`.
- Adds `printK300TsplMinText()`.
- Adds `printK300TsplBlackBox()`.
- Adds `testK300SppConnection()`.
- Prints exactly one STORE_ITEM preview label.
- Supports 60x40 and 40x30 gap labels.
- Requires `machine_code` to be numeric and start with `5`.
- Android only prints FW-ERP-provided barcode payload.
- Sends raw TSPL only through the explicit raw TSPL diagnostic method.
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
  "urovo_printer_available": false,
  "urovo_last_status_code": null,
  "urovo_last_status_text": "",
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
- No bundled Zebra SDK, Honeywell SDK, Urovo SDK jar, CameraX scanner flow, POS
  logic, production printing, or offline queue. Urovo K300 diagnostics call the
  device-provided `android.device.PrinterManager` by reflection only.
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
