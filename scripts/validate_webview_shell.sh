#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MAIN_ACTIVITY="$ROOT_DIR/app/src/main/java/com/directloop/pda/MainActivity.kt"
MANIFEST="$ROOT_DIR/app/src/main/AndroidManifest.xml"
APP_GRADLE="$ROOT_DIR/app/build.gradle.kts"
README="$ROOT_DIR/README.md"
STYLES="$ROOT_DIR/app/src/main/res/values/styles.xml"

test -f "$MAIN_ACTIVITY"
test -f "$MANIFEST"
test -f "$APP_GRADLE"
test -f "$README"
test -f "$STYLES"

grep -q 'kotlin("android")' "$APP_GRADLE"
grep -q 'applicationId = "com.directloop.pda"' "$APP_GRADLE"
grep -q 'android:label="@string/app_name"' "$MANIFEST"
grep -q 'android:screenOrientation="portrait"' "$MANIFEST"
grep -q 'android:windowSoftInputMode="adjustResize"' "$MANIFEST"
grep -q 'Theme.Material.Light.NoActionBar' "$STYLES"
grep -q 'android:windowNoTitle' "$STYLES"
grep -q 'android:windowActionBar">false' "$STYLES"
grep -q 'android:windowFullscreen">true' "$STYLES"
grep -q 'android.permission.INTERNET' "$MANIFEST"
grep -q 'android.permission.CAMERA' "$MANIFEST"
grep -q 'FW_ERP_APP_URL' "$APP_GRADLE"
grep -q 'FW_ERP_HOST' "$APP_GRADLE"
grep -q 'https://fw-erp-34-35-52-250.nip.io/app/' "$APP_GRADLE"
grep -q 'fw-erp-34-35-52-250.nip.io' "$APP_GRADLE"
grep -q 'BuildConfig.FW_ERP_APP_URL' "$MAIN_ACTIVITY"
grep -q 'BuildConfig.FW_ERP_HOST' "$MAIN_ACTIVITY"
if grep -q 'https://fw-erp-34-35-52-250.nip.io/app/' "$MAIN_ACTIVITY"; then
  echo "MainActivity must read the FW-ERP URL from BuildConfig, not a hardcoded string." >&2
  exit 1
fi
if grep -q 'private const val PRODUCTION_APP_URL\\|private const val PRODUCTION_HOST' "$MAIN_ACTIVITY"; then
  echo "MainActivity must not keep old hardcoded production endpoint constants." >&2
  exit 1
fi
grep -q 'javaScriptEnabled = true' "$MAIN_ACTIVITY"
grep -q 'domStorageEnabled = true' "$MAIN_ACTIVITY"
grep -q 'CookieManager.getInstance()' "$MAIN_ACTIVITY"
grep -q 'CookieManager.getInstance().flush()' "$MAIN_ACTIVITY"
grep -q 'onShowFileChooser' "$MAIN_ACTIVITY"
grep -q 'WebView canGoBack' "$MAIN_ACTIVITY"
grep -q 'offlineContainer' "$MAIN_ACTIVITY"
grep -q 'loadingContainer' "$MAIN_ACTIVITY"
grep -q 'Direct Loop PDA' "$MAIN_ACTIVITY"
grep -q 'ProgressBar' "$MAIN_ACTIVITY"
grep -q 'showLoadingScreen' "$MAIN_ACTIVITY"
grep -q 'hideLoadingScreen' "$MAIN_ACTIVITY"
grep -q 'onPageFinished' "$MAIN_ACTIVITY"
grep -q 'currentUrlText' "$MAIN_ACTIVITY"
grep -q 'retryLastUrl' "$MAIN_ACTIVITY"
grep -q 'hideSystemUi' "$MAIN_ACTIVITY"
grep -q 'WindowInsets' "$MAIN_ACTIVITY"
grep -q 'WindowInsets.Type.ime()' "$MAIN_ACTIVITY"
grep -q 'setOnApplyWindowInsetsListener' "$MAIN_ACTIVITY"
grep -q 'SYSTEM_UI_FLAG_IMMERSIVE_STICKY' "$MAIN_ACTIVITY"
grep -q 'SOFT_INPUT_ADJUST_RESIZE' "$MAIN_ACTIVITY"
grep -q 'FW-ERP `/app/`' "$README"
grep -q '#195 PDA UI is reused through /app' "$README"
grep -q 'Native scanner, Bluetooth printing, and offline queue' "$README"
grep -q 'WebView only: no ERP API duplication' "$README"
grep -q 'Android only creates print jobs' "$README"
grep -q 'FW-ERP Print Agent' "$README"
grep -q '60x40 / 40x30' "$README"
grep -q 'native print APIs' "$README"
grep -q 'send TSPL' "$README"

if grep -q 'clearCache\\|clearHistory\\|clearFormData\\|CookieManager.getInstance().remove\\|WebStorage.getInstance().delete' "$MAIN_ACTIVITY"; then
  echo "Android shell must not proactively clear WebView cookies or storage." >&2
  exit 1
fi

if grep -R --exclude='MainActivity.kt' --exclude='README.md' -E '/api/v1|OkHttp|Retrofit|HttpURLConnection' "$ROOT_DIR/app" >/dev/null 2>&1; then
  echo "Android shell must not add direct ERP API clients." >&2
  exit 1
fi

if grep -R -E 'BluetoothAdapter|BluetoothSocket|BluetoothManager|TSPL|Tspl|PrintManager|PrintDocumentAdapter' "$ROOT_DIR/app" >/dev/null 2>&1; then
  echo "Android shell must stay WebView-only and must not add Bluetooth, TSPL, or native print APIs." >&2
  exit 1
fi

if find "$ROOT_DIR" -path '*/frontend_prototype/*' -o -path '*/backend/*' | grep -q .; then
  echo "Android repo must not copy FW-ERP frontend or backend files." >&2
  exit 1
fi

echo "FW-ERP Android WebView shell contract OK"
