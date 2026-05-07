#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MAIN_ACTIVITY="$ROOT_DIR/app/src/main/java/com/directloop/pda/MainActivity.kt"
MANIFEST="$ROOT_DIR/app/src/main/AndroidManifest.xml"
APP_GRADLE="$ROOT_DIR/app/build.gradle.kts"
README="$ROOT_DIR/README.md"

test -f "$MAIN_ACTIVITY"
test -f "$MANIFEST"
test -f "$APP_GRADLE"
test -f "$README"

grep -q 'kotlin("android")' "$APP_GRADLE"
grep -q 'applicationId = "com.directloop.pda"' "$APP_GRADLE"
grep -q 'android:label="@string/app_name"' "$MANIFEST"
grep -q 'android:screenOrientation="portrait"' "$MANIFEST"
grep -q 'android.permission.INTERNET' "$MANIFEST"
grep -q 'android.permission.CAMERA' "$MANIFEST"
grep -q 'https://fw-erp-34-35-52-250.nip.io/app/' "$MAIN_ACTIVITY"
grep -q 'javaScriptEnabled = true' "$MAIN_ACTIVITY"
grep -q 'domStorageEnabled = true' "$MAIN_ACTIVITY"
grep -q 'CookieManager.getInstance()' "$MAIN_ACTIVITY"
grep -q 'onShowFileChooser' "$MAIN_ACTIVITY"
grep -q 'WebView canGoBack' "$MAIN_ACTIVITY"
grep -q 'offlineContainer' "$MAIN_ACTIVITY"
grep -q 'FW-ERP `/app/`' "$README"
grep -q '#195 PDA UI is reused through /app' "$README"
grep -q 'Native scanner, Bluetooth printing, and offline queue' "$README"

if grep -R --exclude='MainActivity.kt' --exclude='README.md' -E '/api/v1|OkHttp|Retrofit|HttpURLConnection' "$ROOT_DIR/app" >/dev/null 2>&1; then
  echo "Android shell must not add direct ERP API clients." >&2
  exit 1
fi

if find "$ROOT_DIR" -path '*/frontend_prototype/*' -o -path '*/backend/*' | grep -q .; then
  echo "Android repo must not copy FW-ERP frontend or backend files." >&2
  exit 1
fi

echo "FW-ERP Android WebView shell contract OK"
