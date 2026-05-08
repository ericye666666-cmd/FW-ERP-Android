#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MAIN_ACTIVITY="$ROOT_DIR/app/src/main/java/com/directloop/pda/MainActivity.kt"
MANIFEST="$ROOT_DIR/app/src/main/AndroidManifest.xml"
APP_GRADLE="$ROOT_DIR/app/build.gradle.kts"
README="$ROOT_DIR/README.md"
STYLES="$ROOT_DIR/app/src/main/res/values/styles.xml"
GRADLEW="$ROOT_DIR/gradlew"
WRAPPER_PROPS="$ROOT_DIR/gradle/wrapper/gradle-wrapper.properties"
WRAPPER_JAR="$ROOT_DIR/gradle/wrapper/gradle-wrapper.jar"
APK_WORKFLOW="$ROOT_DIR/.github/workflows/debug-apk.yml"

test -f "$MAIN_ACTIVITY"
test -f "$MANIFEST"
test -f "$APP_GRADLE"
test -f "$README"
test -f "$STYLES"
test -x "$GRADLEW"
test -f "$WRAPPER_PROPS"
test -f "$WRAPPER_JAR"
test -f "$APK_WORKFLOW"

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
grep -q 'sourceCompatibility = JavaVersion.VERSION_17' "$APP_GRADLE"
grep -q 'targetCompatibility = JavaVersion.VERSION_17' "$APP_GRADLE"
if grep -q 'localhost\\|127\\.0\\.0\\.1' "$APP_GRADLE"; then
  echo "Debug APK must keep the configured FW-ERP staging URL, not a local dev URL." >&2
  exit 1
fi
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
grep -q 'SCANNER_INPUT_FOCUS_SCRIPT' "$MAIN_ACTIVITY"
grep -q 'focusScannerInput' "$MAIN_ACTIVITY"
grep -q 'evaluateJavascript(SCANNER_INPUT_FOCUS_SCRIPT, null)' "$MAIN_ACTIVITY"
grep -q 'onPageFinished' "$MAIN_ACTIVITY"
grep -q "data-scan-input') === 'true'" "$MAIN_ACTIVITY"
grep -q 'return 1000' "$MAIN_ACTIVITY"
grep -q 'barcode' "$MAIN_ACTIVITY"
grep -q 'return 800' "$MAIN_ACTIVITY"
grep -q 'scan' "$MAIN_ACTIVITY"
grep -q 'machine_code' "$MAIN_ACTIVITY"
grep -q 'type === "search"' "$MAIN_ACTIVITY"
grep -q 'return 100' "$MAIN_ACTIVITY"
grep -q 'activeScore >= bestScore' "$MAIN_ACTIVITY"
grep -q 'FW-ERP `/app/`' "$README"
grep -q '#195 PDA UI is reused through /app' "$README"
grep -q 'Native scanner, Bluetooth printing, and offline queue' "$README"
grep -q 'PDA scanner input mode' "$README"
grep -q 'hardware Enter' "$README"
grep -q 'No native scanner SDK' "$README"
grep -q 'GitHub repository' "$README"
grep -q 'Actions' "$README"
grep -q 'direct-loop-pda-debug-apk' "$README"
grep -q 'direct-loop-pda-debug.apk' "$README"
grep -q 'Unzip' "$README"
grep -q 'unknown sources' "$README"
grep -q 'https://fw-erp-34-35-52-250.nip.io/app/' "$README"

grep -q 'distributionUrl=' "$WRAPPER_PROPS"
grep -q 'gradle-' "$WRAPPER_PROPS"
grep -q 'push:' "$APK_WORKFLOW"
grep -Fq 'branches: [ main ]' "$APK_WORKFLOW"
grep -q 'pull_request:' "$APK_WORKFLOW"
grep -q 'workflow_dispatch:' "$APK_WORKFLOW"
grep -q './gradlew assembleDebug' "$APK_WORKFLOW"
grep -q 'actions/upload-artifact' "$APK_WORKFLOW"
grep -q 'direct-loop-pda-debug-apk' "$APK_WORKFLOW"
grep -q 'direct-loop-pda-debug.apk' "$APK_WORKFLOW"
if grep -q 'localhost\\|127\\.0\\.0\\.1' "$APK_WORKFLOW"; then
  echo "GitHub Actions debug APK build must not point the app at a local dev URL." >&2
  exit 1
fi

if grep -q 'KEYCODE_ENTER\|setOnKeyListener\|dispatchKeyEvent' "$MAIN_ACTIVITY"; then
  echo "Android shell must let hardware Enter reach the focused FW-ERP input." >&2
  exit 1
fi

if grep -q 'clearCache\\|clearHistory\\|clearFormData\\|CookieManager.getInstance().remove\\|WebStorage.getInstance().delete' "$MAIN_ACTIVITY"; then
  echo "Android shell must not proactively clear WebView cookies or storage." >&2
  exit 1
fi

if grep -R --exclude='MainActivity.kt' --exclude='README.md' -E '/api/v1|OkHttp|Retrofit|HttpURLConnection' "$ROOT_DIR/app" >/dev/null 2>&1; then
  echo "Android shell must not add direct ERP API clients." >&2
  exit 1
fi

if grep -R -E 'Zebra|Honeywell|Urovo|CameraX|androidx\.camera' "$ROOT_DIR/app" "$ROOT_DIR/build.gradle.kts" "$ROOT_DIR/settings.gradle.kts" >/dev/null 2>&1; then
  echo "Android shell must not add native scanner SDKs or CameraX for keyboard-mode scanners." >&2
  exit 1
fi

if find "$ROOT_DIR" -path '*/frontend_prototype/*' -o -path '*/backend/*' | grep -q .; then
  echo "Android repo must not copy FW-ERP frontend or backend files." >&2
  exit 1
fi

echo "FW-ERP Android WebView shell contract OK"
