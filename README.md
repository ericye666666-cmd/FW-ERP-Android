# Direct Loop PDA

Direct Loop PDA is the first Android WebView shell for FW-ERP.

The Android app loads the existing FW-ERP `/app/`:

```text
https://fw-erp-34-35-52-250.nip.io/app/
```

## Scope

- This Android app is a WebView shell.
- UI and APIs are served by FW-ERP.
- #195 PDA UI is reused through /app.
- Data remains in the FW-ERP backend.
- Android does not duplicate ERP business logic.
- Android does not call FW-ERP APIs directly yet.
- Native scanner, Bluetooth printing, and offline queue will be separate future PRs.

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

## Non-goals

- No native rebuild of #195 screens.
- No copied FW-ERP frontend files.
- No duplicate Android API clients.
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
