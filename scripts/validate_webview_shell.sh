#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MAIN_ACTIVITY="$ROOT_DIR/app/src/main/java/com/directloop/pda/MainActivity.kt"
PRINTER_BRIDGE="$ROOT_DIR/app/src/main/java/com/directloop/pda/DirectLoopPdaPrinterBridge.kt"
PRINTER_PAYLOADS="$ROOT_DIR/app/src/main/java/com/directloop/pda/BluetoothPrinterTestPayloads.kt"
CHITENG_CLIENT="$ROOT_DIR/app/src/main/java/com/directloop/pda/ChitengS1OfficialPrinterClient.kt"
CHITENG_JAR="$ROOT_DIR/app/libs/ctaiotCtpl1.1.8.jar"
MANIFEST="$ROOT_DIR/app/src/main/AndroidManifest.xml"
APP_GRADLE="$ROOT_DIR/app/build.gradle.kts"
README="$ROOT_DIR/README.md"
STYLES="$ROOT_DIR/app/src/main/res/values/styles.xml"
GRADLEW="$ROOT_DIR/gradlew"
WRAPPER_PROPS="$ROOT_DIR/gradle/wrapper/gradle-wrapper.properties"
WRAPPER_JAR="$ROOT_DIR/gradle/wrapper/gradle-wrapper.jar"
APK_WORKFLOW="$ROOT_DIR/.github/workflows/debug-apk.yml"

test -f "$MAIN_ACTIVITY"
test -f "$PRINTER_BRIDGE"
test -f "$PRINTER_PAYLOADS"
test -f "$CHITENG_CLIENT"
test -f "$CHITENG_JAR"
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
grep -q 'android.permission.BLUETOOTH"' "$MANIFEST"
grep -q 'android.permission.BLUETOOTH_ADMIN' "$MANIFEST"
grep -q 'android.permission.ACCESS_FINE_LOCATION' "$MANIFEST"
grep -q 'android.permission.BLUETOOTH_CONNECT' "$MANIFEST"
grep -q 'android.permission.BLUETOOTH_SCAN' "$MANIFEST"
grep -q 'FW_ERP_APP_URL' "$APP_GRADLE"
grep -q 'FW_ERP_HOST' "$APP_GRADLE"
grep -q 'https://fw-erp-34-35-52-250.nip.io/app/' "$APP_GRADLE"
grep -q 'fw-erp-34-35-52-250.nip.io' "$APP_GRADLE"
grep -q 'sourceCompatibility = JavaVersion.VERSION_17' "$APP_GRADLE"
grep -q 'targetCompatibility = JavaVersion.VERSION_17' "$APP_GRADLE"
grep -Fq 'implementation(files("libs/ctaiotCtpl1.1.8.jar"))' "$APP_GRADLE"
grep -q 'PDA_DEBUG_KEYSTORE_PATH' "$APP_GRADLE"
grep -q 'PDA_DEBUG_KEYSTORE_PASSWORD' "$APP_GRADLE"
grep -q 'PDA_DEBUG_KEY_ALIAS' "$APP_GRADLE"
grep -q 'PDA_DEBUG_KEY_PASSWORD' "$APP_GRADLE"
grep -q 'pdaDebugSigningEnvComplete' "$APP_GRADLE"
grep -q 'signingConfigs.create("pdaDebug")' "$APP_GRADLE"
grep -q 'debug.signingConfig = signingConfigs.getByName("pdaDebug")' "$APP_GRADLE"
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
grep -q 'DirectLoopPdaPrinter' "$MAIN_ACTIVITY"
grep -q 'addJavascriptInterface' "$MAIN_ACTIVITY"
grep -q 'domStorageEnabled = true' "$MAIN_ACTIVITY"
grep -q 'databaseEnabled = true' "$MAIN_ACTIVITY"
grep -q 'cacheMode = WebSettings.LOAD_NO_CACHE' "$MAIN_ACTIVITY"
grep -q 'CookieManager.getInstance()' "$MAIN_ACTIVITY"
grep -q 'setAcceptCookie(true)' "$MAIN_ACTIVITY"
grep -q 'setAcceptThirdPartyCookies(webView, true)' "$MAIN_ACTIVITY"
grep -q 'CookieManager.getInstance().flush()' "$MAIN_ACTIVITY"
grep -q 'WEB_SESSION_STORAGE_PROBE_SCRIPT' "$MAIN_ACTIVITY"
grep -q 'probeWebSessionStorage(view)' "$MAIN_ACTIVITY"
grep -q 'retail_ops_access_token' "$MAIN_ACTIVITY"
grep -q 'retail_ops_current_user' "$MAIN_ACTIVITY"
grep -q 'retail_ops_api_base' "$MAIN_ACTIVITY"
grep -q 'lastPauseAt' "$MAIN_ACTIVITY"
grep -q 'lastFreshLoadAt' "$MAIN_ACTIVITY"
grep -q 'hasFinishedInitialLoad' "$MAIN_ACTIVITY"
grep -q 'getCacheBustedAppUrl' "$MAIN_ACTIVITY"
grep -q 'pda_reload=' "$MAIN_ACTIVITY"
grep -q 'loadFreshApp' "$MAIN_ACTIVITY"
grep -q 'webView.loadUrl(getCacheBustedAppUrl())' "$MAIN_ACTIVITY"
grep -q 'lastFreshLoadAt = now' "$MAIN_ACTIVITY"
grep -q 'mainFrameLoadFailed = false' "$MAIN_ACTIVITY"
grep -q 'override fun onResume' "$MAIN_ACTIVITY"
grep -q 'now - lastPauseAt > FRESH_LOAD_INTERVAL_MS' "$MAIN_ACTIVITY"
grep -q 'now - lastFreshLoadAt > FRESH_LOAD_INTERVAL_MS' "$MAIN_ACTIVITY"
grep -q 'override fun onPause' "$MAIN_ACTIVITY"
grep -q 'lastPauseAt = System.currentTimeMillis()' "$MAIN_ACTIVITY"
grep -q 'hasFinishedInitialLoad = true' "$MAIN_ACTIVITY"
grep -q 'FRESH_LOAD_INTERVAL_MS = 5000L' "$MAIN_ACTIVITY"
if grep -q 'restoreState' "$MAIN_ACTIVITY"; then
  echo "MainActivity must not keep a restored WebView state as the final loaded page; load a fresh /app/ URL instead." >&2
  exit 1
fi
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
grep -q 'syncPrinterBridgeTrustedPage' "$MAIN_ACTIVITY"
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
grep -q 'Native scanner and offline queue will be separate future PRs' "$README"
grep -q 'Bluetooth printer production printing remains future work' "$README"
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
grep -q 'Stable internal debug signing' "$README"
grep -q 'PDA_DEBUG_KEYSTORE_BASE64' "$README"
grep -q 'PDA_DEBUG_KEYSTORE_PASSWORD' "$README"
grep -q 'PDA_DEBUG_KEY_ALIAS' "$README"
grep -q 'PDA_DEBUG_KEY_PASSWORD' "$README"
grep -q 'Rotate the key' "$README"
grep -q 'uninstall once' "$README"
grep -q 'Austin / demo1234' "$README"
grep -q '店员 PDA 工作台' "$README"
grep -q 'Close and reopen' "$README"
grep -q 'retail_ops_access_token' "$README"
grep -q 'retail_ops_current_user' "$README"
grep -q 'retail_ops_api_base' "$README"
grep -q 'Clerk PDA' "$README"
grep -q '蓝牙打印机测试' "$README"
grep -q 'window.DirectLoopPdaPrinter' "$README"
grep -q 'getAppInfo()' "$README"
grep -q 'bridge_version' "$README"
grep -q 'pda-android-20260510-appinfo' "$README"
grep -q 'startPrinterDiscovery' "$README"
grep -q 'getDiscoveredPrinters' "$README"
grep -q 'CHITENG_S1' "$README"
grep -q 'UROVO' "$README"
grep -q 'GENERIC' "$README"
grep -q 'TSPL' "$README"
grep -q 'CPCL' "$README"
grep -q 'ESC_POS' "$README"
grep -q 'TSPL_SIMPLE_TEXT' "$README"
grep -q 'TSPL_DENSITY_TEXT' "$README"
grep -q 'TSPL_NO_GAP_CONTINUOUS' "$README"
grep -q 'TSPL_GAP_DETECT' "$README"
grep -q 'RAW_LF_FEED' "$README"
grep -q 'ESC_POS_TEXT' "$README"
grep -q 'CPCL_SIMPLE_TEXT' "$README"
grep -q 'CHITENG_S1_OFFICIAL' "$README"
grep -q 'TSPL feeds paper but prints no content' "$README"
grep -q 'printStoreItemLabelPreview(payloadJson)' "$README"
grep -q 'Prints exactly one STORE_ITEM preview label' "$README"
grep -q 'last_preview_transport' "$README"
grep -q 'last_preview_tspl_command' "$README"
grep -q 'STORE_ITEM_LABEL_PREVIEW_TSPL' "$README"
grep -q 'RAW_TSPL_SPP' "$README"
grep -q 'No batch printing' "$README"
grep -q 'No barcode generation in Android' "$README"
grep -q 'printStoreItemLabelPreview(payloadJson)' "$ROOT_DIR/docs/chiteng-s1-integration-notes.md"
grep -q 'Preview print only supports exactly one STORE_ITEM label.' "$ROOT_DIR/docs/chiteng-s1-integration-notes.md"
grep -q 'machine_code must be numeric and start with 5' "$ROOT_DIR/docs/chiteng-s1-integration-notes.md"

grep -q '@JavascriptInterface' "$PRINTER_BRIDGE"
grep -q 'fun getAppInfo()' "$PRINTER_BRIDGE"
sed -n '/fun getAppInfo()/,/fun getPrinterStatus()/p' "$PRINTER_BRIDGE" | grep -q 'isTrustedPage()'
grep -q 'BuildConfig.VERSION_NAME' "$PRINTER_BRIDGE"
grep -q 'BuildConfig.VERSION_CODE' "$PRINTER_BRIDGE"
grep -q 'BuildConfig.APPLICATION_ID' "$PRINTER_BRIDGE"
grep -q 'package_name' "$PRINTER_BRIDGE"
grep -q 'bridge_version' "$PRINTER_BRIDGE"
grep -q 'pda-android-20260510-appinfo' "$PRINTER_BRIDGE"
grep -q 'supported_methods' "$PRINTER_BRIDGE"
grep -q 'fun getPrinterStatus()' "$PRINTER_BRIDGE"
grep -q 'fun listPairedPrinters()' "$PRINTER_BRIDGE"
grep -q 'fun startPrinterDiscovery()' "$PRINTER_BRIDGE"
grep -q 'fun stopPrinterDiscovery()' "$PRINTER_BRIDGE"
grep -q 'fun getDiscoveredPrinters()' "$PRINTER_BRIDGE"
grep -q 'fun connectPrinter' "$PRINTER_BRIDGE"
grep -q 'fun disconnectPrinter()' "$PRINTER_BRIDGE"
grep -q 'fun printTestLabel' "$PRINTER_BRIDGE"
grep -q 'fun getLastPrintResult()' "$PRINTER_BRIDGE"
grep -q 'ChitengS1OfficialPrinterClient' "$PRINTER_BRIDGE"
grep -q 'printOfficialChitengTestLabel' "$PRINTER_BRIDGE"
grep -q 'fun printStoreItemLabelPreview' "$PRINTER_BRIDGE"
grep -q 'printOfficialStoreItemLabelPreview' "$PRINTER_BRIDGE"
grep -q 'STORE_ITEM_LABEL_PREVIEW' "$PRINTER_BRIDGE"
grep -q 'lastPreviewTransport' "$PRINTER_BRIDGE"
grep -q 'lastPreviewLabelSize' "$PRINTER_BRIDGE"
grep -q 'lastPreviewTsplCommand' "$PRINTER_BRIDGE"
grep -q 'lastPreviewTsplSentAt' "$PRINTER_BRIDGE"
grep -q 'lastPreviewTsplBytes' "$PRINTER_BRIDGE"
grep -q 'PREVIEW_TRANSPORT_RAW_TSPL_SPP' "$PRINTER_BRIDGE"
grep -q 'last_preview_transport' "$PRINTER_BRIDGE"
grep -q 'last_preview_label_size' "$PRINTER_BRIDGE"
grep -q 'last_preview_tspl_command' "$PRINTER_BRIDGE"
grep -q 'last_preview_tspl_lines' "$PRINTER_BRIDGE"
grep -q 'last_preview_tspl_sent_at' "$PRINTER_BRIDGE"
grep -q 'last_preview_tspl_bytes' "$PRINTER_BRIDGE"
if grep -q 'fun printStoreItemLabels' "$PRINTER_BRIDGE"; then
  echo "PR #19 must not expose batch STORE_ITEM printing through printStoreItemLabels." >&2
  exit 1
fi
grep -q '00001101-0000-1000-8000-00805F9B34FB' "$PRINTER_BRIDGE"
grep -q 'BuildConfig.FW_ERP_HOST' "$PRINTER_BRIDGE"
grep -q 'bridge_available' "$PRINTER_BRIDGE"
grep -q 'paired_printer_count' "$PRINTER_BRIDGE"
grep -q 'paired_printers' "$PRINTER_BRIDGE"
grep -q 'discovery_status' "$PRINTER_BRIDGE"
grep -q 'discovered_printer_count' "$PRINTER_BRIDGE"
grep -q 'discovered_printers' "$PRINTER_BRIDGE"
grep -q 'ACTION_FOUND' "$PRINTER_BRIDGE"
grep -q 'ACTION_DISCOVERY_FINISHED' "$PRINTER_BRIDGE"
grep -q 'DISCOVERY_TIMEOUT_MS = 15000L' "$PRINTER_BRIDGE"
grep -q '请先在 Android 系统蓝牙中完成配对后再连接。' "$PRINTER_BRIDGE"
grep -q 'connection_status' "$PRINTER_BRIDGE"
grep -q 'printer_online_status' "$PRINTER_BRIDGE"
grep -q 'printer_health_checked_at' "$PRINTER_BRIDGE"
grep -q 'official_sdk_available' "$PRINTER_BRIDGE"
grep -q 'official_sdk_connected' "$PRINTER_BRIDGE"
grep -q 'official_sdk_last_message' "$PRINTER_BRIDGE"
grep -q 'official_sdk_last_error' "$PRINTER_BRIDGE"
grep -q 'online_status' "$PRINTER_BRIDGE"
grep -q 'SOURCE_PAIRED = "paired"' "$PRINTER_BRIDGE"
grep -q 'SOURCE_DISCOVERED = "discovered"' "$PRINTER_BRIDGE"
grep -q 'last_print_result' "$PRINTER_BRIDGE"
grep -q 'bluetooth_enabled' "$PRINTER_BRIDGE"
grep -q 'BLUETOOTH_CONNECT' "$PRINTER_BRIDGE"
grep -q 'BLUETOOTH_SCAN' "$PRINTER_BRIDGE"
grep -q 'ACCESS_FINE_LOCATION' "$PRINTER_BRIDGE"
grep -q 'stopPrinterDiscoveryInternal' "$PRINTER_BRIDGE"
grep -q 'pdaPrinterBridge.destroy()' "$MAIN_ACTIVITY"
grep -q 'CHITENG_S1' "$PRINTER_BRIDGE"
grep -q 'UROVO' "$PRINTER_BRIDGE"
grep -q 'GENERIC' "$PRINTER_BRIDGE"
grep -q 'TSPL' "$PRINTER_PAYLOADS"
grep -q 'CPCL' "$PRINTER_PAYLOADS"
grep -q 'ESC_POS' "$PRINTER_PAYLOADS"
grep -q 'TSPL_SIMPLE_TEXT' "$PRINTER_PAYLOADS"
grep -q 'TSPL_DENSITY_TEXT' "$PRINTER_PAYLOADS"
grep -q 'TSPL_NO_GAP_CONTINUOUS' "$PRINTER_PAYLOADS"
grep -q 'TSPL_GAP_DETECT' "$PRINTER_PAYLOADS"
grep -q 'RAW_LF_FEED' "$PRINTER_PAYLOADS"
grep -q 'ESC_POS_TEXT' "$PRINTER_PAYLOADS"
grep -q 'CPCL_SIMPLE_TEXT' "$PRINTER_PAYLOADS"
grep -q 'CHITENG_S1_OFFICIAL' "$PRINTER_PAYLOADS"
grep -q 'GAPDETECT' "$PRINTER_PAYLOADS"
grep -q 'DENSITY 12' "$PRINTER_PAYLOADS"
grep -q 'SPEED 2' "$PRINTER_PAYLOADS"
grep -q 'SET TEAR ON' "$PRINTER_PAYLOADS"
grep -q 'S1 TEST' "$PRINTER_PAYLOADS"
grep -q 'DIRECT LOOP' "$PRINTER_PAYLOADS"
grep -q 'PRINTER TEST' "$PRINTER_PAYLOADS"
grep -q 'TEST123456' "$PRINTER_PAYLOADS"
grep -q 'MODEL:' "$PRINTER_PAYLOADS"
grep -q 'CTPL.getInstance()' "$CHITENG_CLIENT"
grep -q 'RespCallback' "$CHITENG_CLIENT"
grep -q 'setSize(60, 40)' "$CHITENG_CLIENT"
grep -q 'drawText' "$CHITENG_CLIENT"
grep -q 'drawBarCode' "$CHITENG_CLIENT"
grep -q 'CODE_128' "$CHITENG_CLIENT"
grep -q 'StoreItemLabelPreviewPayload' "$CHITENG_CLIENT"
grep -q 'StoreItemLabelRow' "$CHITENG_CLIENT"
grep -q 'toRawTspl' "$CHITENG_CLIENT"
grep -q 'SIZE 40 mm,30 mm' "$CHITENG_CLIENT"
grep -q 'SIZE 60 mm,40 mm' "$CHITENG_CLIENT"
grep -Fq 'BARCODE 20,105,\"128\",70,1,0,2,2' "$CHITENG_CLIENT"
grep -Fq 'BARCODE 32,145,\"128\",90,1,0,2,2' "$CHITENG_CLIENT"
grep -q 'PRINT 1,1' "$CHITENG_CLIENT"
grep -Fq 'separator = "\r\n", postfix = "\r\n"' "$CHITENG_CLIENT"
grep -q 'Preview print only supports exactly one STORE_ITEM label.' "$CHITENG_CLIENT"
grep -q 'machine_code' "$CHITENG_CLIENT"
grep -q 'barcode_value' "$CHITENG_CLIENT"
grep -q 'price_kes' "$CHITENG_CLIENT"
grep -q 'category_short' "$CHITENG_CLIENT"
grep -q 'label_template_size' "$CHITENG_CLIENT"
grep -q 'startsWith("5")' "$CHITENG_CLIENT"
grep -q 'grade or pricing_type is required.' "$CHITENG_CLIENT"
grep -q 'STORE_ITEM_LABEL_PREVIEW_TSPL' "$PRINTER_BRIDGE"
grep -q 'Charset.forName("GBK")' "$PRINTER_BRIDGE"
grep -q 'sendRawTsplOverSpp' "$PRINTER_BRIDGE"
grep -q 'PREVIEW_PRINT_BUSY_WINDOW_MS = 8000L' "$PRINTER_BRIDGE"
grep -q 'Printer is busy. Wait before printing again.' "$PRINTER_BRIDGE"
if awk '
  /private fun printOfficialStoreItemLabelPreview/ { in_preview = 1 }
  in_preview && /private fun guardedStatus/ { in_preview = 0 }
  in_preview && /(setBackpressure|drawBitmap|Label_Divide|GAPDETECT|FORMFEED|FEED)/ { found = 1 }
  END { exit found ? 0 : 1 }
' "$PRINTER_BRIDGE" >/dev/null; then
  echo "STORE_ITEM raw TSPL preview must not use CTPL bitmap, backpressure, calibration, feed, or Label_Divide." >&2
  exit 1
fi
if grep -q 'fun printStoreItemLabels' "$CHITENG_CLIENT"; then
  echo "PR #19 must not implement batch STORE_ITEM printing in ChitengS1OfficialPrinterClient." >&2
  exit 1
fi
grep -q 'TEST123456' "$CHITENG_CLIENT"
grep -q 'verifyConnection' "$CHITENG_CLIENT"
grep -q 'queryPrintState' "$CHITENG_CLIENT"
grep -q 'onDataResponse' "$CHITENG_CLIENT"

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
grep -q 'PDA_DEBUG_KEYSTORE_BASE64' "$APK_WORKFLOW"
grep -q 'secrets.PDA_DEBUG_KEYSTORE_BASE64' "$APK_WORKFLOW"
grep -q 'secrets.PDA_DEBUG_KEYSTORE_PASSWORD' "$APK_WORKFLOW"
grep -q 'secrets.PDA_DEBUG_KEY_ALIAS' "$APK_WORKFLOW"
grep -q 'secrets.PDA_DEBUG_KEY_PASSWORD' "$APK_WORKFLOW"
grep -q 'RUNNER_TEMP/pda-debug.keystore' "$APK_WORKFLOW"
grep -q 'base64 --decode' "$APK_WORKFLOW"
grep -q 'PDA_DEBUG_KEYSTORE_PATH' "$APK_WORKFLOW"
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

if sed -n '/override fun onResume/,/^    }/p' "$MAIN_ACTIVITY" | grep -q 'loadUrl\|reload'; then
  echo "Android shell must not reload FW-ERP during onResume; it can bounce a valid login session." >&2
  exit 1
fi

if sed -n '/private fun retryLastUrl/,/^    }/p' "$MAIN_ACTIVITY" | grep -q 'BuildConfig.FW_ERP_APP_URL'; then
  echo "Retry must target the failed URL, not blindly reload the app root over a valid session." >&2
  exit 1
fi

if grep -R --exclude='MainActivity.kt' --exclude='README.md' -E '/api/v1|OkHttp|Retrofit|HttpURLConnection' "$ROOT_DIR/app" >/dev/null 2>&1; then
  echo "Android shell must not add direct ERP API clients." >&2
  exit 1
fi

if grep -R -E 'Zebra|Honeywell|CameraX|androidx\.camera|UrovoScanner|urovo\.scanner' "$ROOT_DIR/app" "$ROOT_DIR/build.gradle.kts" "$ROOT_DIR/settings.gradle.kts" >/dev/null 2>&1; then
  echo "Android shell must not add native scanner SDKs or CameraX for keyboard-mode scanners." >&2
  exit 1
fi

if find "$ROOT_DIR" -path '*/frontend_prototype/*' -o -path '*/backend/*' | grep -q .; then
  echo "Android repo must not copy FW-ERP frontend or backend files." >&2
  exit 1
fi

if find "$ROOT_DIR" \
  \( -path "$ROOT_DIR/.git" -o -path "$ROOT_DIR/.gradle" -o -path "$ROOT_DIR/build" -o -path "$ROOT_DIR/app/build" \) -prune -o \
  \( -name '*.jks' -o -name '*.keystore' -o -name 'local.properties' -o -name '*.apk' -o -name '*.aab' \) -print | grep -q .; then
  echo "Repository must not commit keystores, local.properties, APKs, AABs, or build outputs." >&2
  exit 1
fi

if grep -R -E 'storePassword\\s*=\\s*\"[^$]|keyPassword\\s*=\\s*\"[^$]|PDA_DEBUG_KEYSTORE_BASE64=.+' "$ROOT_DIR" \
  --exclude-dir=.git \
  --exclude-dir=.gradle \
  --exclude-dir=build \
  --exclude-dir=app/build \
  --exclude='validate_webview_shell.sh' >/dev/null 2>&1; then
  echo "Repository must not commit signing passwords or base64 keystore content." >&2
  exit 1
fi

echo "FW-ERP Android WebView shell contract OK"
