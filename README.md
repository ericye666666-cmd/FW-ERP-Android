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
- WebView only: no ERP API duplication and no copied frontend.

## Current behavior

- App name: `Direct Loop PDA`.
- Opens FW-ERP `/app/` in a portrait-only Android WebView.
- Shows a loading view while FW-ERP print queue pages load.
- Enables JavaScript, DOM storage, localStorage/sessionStorage, cookies, and WebView persistence needed for login.
- Flushes cookies without proactively clearing WebView storage.
- Keeps FW-ERP role landing inside the web app:
  - `store_clerk` can reach the PDA clerk flow, including #195 PDA 现场分堆标价 UI.
  - `store_manager` can reach the manager flow when available.
  - `admin` can preview store PDA pages.
- Supports Android back:
  - WebView `goBack()` when history exists.
  - Normal app exit when there is no WebView history.
- Provides a network/offline error view with the current URL and a retry button.
- Uses Android resize behavior so soft keyboard input fields are not fully covered.
- Requests camera permission only for WebView camera/file chooser use.
- Supports WebView file chooser with image selection and camera capture.

## Print Queue Boundary

- Android only creates print jobs by letting the operator use FW-ERP WebView pages.
- Android does not connect to printers, send TSPL, or call native print APIs.
- Real printing is completed by the FW-ERP Print Agent from the FW-ERP print queue.
- Label size selection for `60x40 / 40x30` (60×40 / 40×30) stays in the FW-ERP web page.
- Android does not duplicate FW-ERP APIs, print queue logic, label templates, or barcode generation.

## Non-goals

- No native rebuild of #195 screens.
- No copied FW-ERP frontend files.
- No duplicate Android API clients.
- No Bluetooth permission flow, socket connection, device discovery, or TSPL sending.
- No backend code.
- No hardcoded secrets.
- No APK/build outputs committed.

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
