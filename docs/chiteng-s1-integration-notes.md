# Chiteng S1 Integration Notes

## Scope

This document is the first-stage analysis and design note for integrating the
Chiteng S1 Bluetooth label printer with Direct Loop PDA Android and FW-ERP
Clerk PDA label printing.

It intentionally does not implement production `STORE_ITEM` printing, does not
mark any web print job as printed, and does not change FW-ERP backend, POS,
barcode resolver, task workflow, cookies, localStorage, or WebView session
behavior.

User-provided source folder:

```text
/Users/ericye/Desktop/AI自动化/驰腾开发文件夹
```

Actual local folder found during analysis:

```text
/Users/ericye/Desktop/AI自动化/驰腾开发
```

Related repositories:

```text
/Users/ericye/Desktop/AI自动化/FW-ERP-Android
/Users/ericye/Desktop/AI自动化/FW-ERP
```

## Source Files

### Top-Level Vendor Materials

| 文件名 | 文件路径 | 文件类型 | 主要用途 | 是否需要纳入 Android 项目 | 是否可以提交 GitHub | 是否不应提交 GitHub | 是否需要 Eric 单独确认授权/License |
|---|---|---|---|---|---|---|---|
| `Android-SDK 开发包.zip` | `/Users/ericye/Desktop/AI自动化/驰腾开发/Android-SDK 开发包.zip` | ZIP, 8.9 MB | 官方 Android SDK 包, 包含 Demo 工程、SDK 指南、JAR、APK、Gradle 示例、资源文件。 | 不直接纳入。只从中提取已确认授权的最小依赖。 | 不建议。 | 是, 原始大 ZIP 不应提交。 | 是。ZIP 内没有单独 license 文件。 |
| `驰腾TSPL指令集手册V1.0.pdf` | `/Users/ericye/Desktop/AI自动化/驰腾开发/驰腾TSPL指令集手册V1.0.pdf` | PDF, 559 KB, 24 pages | 驰腾标签机 TSPL 指令手册。用于确认私有 TSPL 语法、字体、换行、纸张和条码命令。 | 不作为运行依赖。只把结论写入项目文档。 | 只有 Eric 明确要求归档供应商 PDF 时才提交。 | 默认不提交。 | 是。 |
| `驰腾票据打印机编程手册esc v1.0.6.pdf` | `/Users/ericye/Desktop/AI自动化/驰腾开发/驰腾票据打印机编程手册esc v1.0.6.pdf` | PDF, 750 KB, 50 pages | 票据打印机 ESC/POS 类指令手册。只适合收据/文本诊断, 不适合作为 S1 标签生产打印主路径。 | 不作为运行依赖。 | 只有 Eric 明确要求归档供应商 PDF 时才提交。 | 默认不提交。 | 是。 |

### Extracted SDK Contents

The SDK ZIP was extracted only for analysis under a temporary path:

```text
/tmp/chiteng-sdk-analysis/Android-SDK 开发包
```

| 文件名 | 文件路径 | 文件类型 | 主要用途 | 是否需要纳入 Android 项目 | 是否可以提交 GitHub | 是否不应提交 GitHub | 是否需要 Eric 单独确认授权/License |
|---|---|---|---|---|---|---|---|
| `CT_Android_SDK开发指南_20240709.pdf` | `/tmp/chiteng-sdk-analysis/Android-SDK 开发包/CT_Android_SDK开发指南_20240709.pdf` | PDF, 707 KB, 31 pages | 官方 Android SDK 接入指南。说明初始化、权限、连接、打印、查询、回调、状态字段。 | 不需要。作为分析依据即可。 | 默认不提交。 | 是, 除非 Eric 要供应商文档归档。 | 是。 |
| `ctaiotCtpl1.1.8.jar` | Source: `/tmp/chiteng-sdk-analysis/Android-SDK 开发包/CTPL_DEMO1.1.8/app/libs/ctaiotCtpl1.1.8.jar`; project copy: `app/libs/ctaiotCtpl1.1.8.jar` | JAR, 55,447 bytes | 官方 CTPL Android SDK 二进制。提供 `CTPL`, `Device`, `RespCallback`, 蓝牙连接、画文字、画条码、画二维码、画图片、状态查询。 | 是。第二阶段已放入 `app/libs/ctaiotCtpl1.1.8.jar`, 仅用于 `CHITENG_S1_OFFICIAL` 诊断打印。 | Eric 已确认允许提交这个 JAR。 | 不应提交其它 SDK ZIP/PDF/Demo/APK。 | Eric 已确认本 JAR 可提交。SHA-256: `409a08b7fc6f21da396818432c927810ca03b56216f3ab2ad79bb65d393aaf85` |
| `CTPL_DEMO1.1.8/app/build.gradle` | `/tmp/chiteng-sdk-analysis/Android-SDK 开发包/CTPL_DEMO1.1.8/app/build.gradle` | Gradle sample | Demo 通过 `implementation fileTree(include: ['*.aar', '*.jar'], dir: 'libs')` 引入 JAR。 | 不复制。只参考依赖写法。 | 不提交。 | 是。 | 否, 但也无需提交。 |
| `CTPL_DEMO1.1.8/app/src/main/AndroidManifest.xml` | `/tmp/chiteng-sdk-analysis/Android-SDK 开发包/CTPL_DEMO1.1.8/app/src/main/AndroidManifest.xml` | Android manifest | 展示权限需求, 包括 Bluetooth、location、USB、storage、Android 12 Bluetooth 权限。 | 不复制。只参考必要权限。 | 不提交。 | 是。 | 否, 但也无需提交。 |
| `CTPL_DEMO1.1.8/app/src/main/java/com/ctaiot/ctprinter/ctpl_demo/FunctionActivity.java` | `/tmp/chiteng-sdk-analysis/Android-SDK 开发包/CTPL_DEMO1.1.8/app/src/main/java/com/ctaiot/ctprinter/ctpl_demo/FunctionActivity.java` | Java demo, 1,639 lines | 核心 Demo。展示 SDK init、SPP/BLE connect、打印文字、条码、二维码、图片、连续打印、状态查询、OTA。 | 不复制业务代码。只复用接入模式。 | 未授权前不提交 vendor demo source。 | 是。 | 是。 |
| `CTPL_DEMO1.1.8/app/src/main/java/com/ctaiot/ctprinter/ctpl_demo/SearchBluetoothActivity.java` | `/tmp/chiteng-sdk-analysis/Android-SDK 开发包/CTPL_DEMO1.1.8/app/src/main/java/com/ctaiot/ctprinter/ctpl_demo/SearchBluetoothActivity.java` | Java demo | 展示 Android SPP discovery 和 BLE scan。SDK JAR 本身未暴露搜索 API, Demo 用 Android 系统 API 搜索。 | 不复制。可按 Direct Loop 现有 discovery bridge 复用思路。 | 不提交。 | 是。 | 是。 |
| `CTPL_DEMO1.1.8/app/src/main/java/com/ctaiot/ctprinter/ctpl_demo/App.java` | `/tmp/chiteng-sdk-analysis/Android-SDK 开发包/CTPL_DEMO1.1.8/app/src/main/java/com/ctaiot/ctprinter/ctpl_demo/App.java` | Java demo | Demo application class, 持有全局 activity reference。 | 不需要。 | 不提交。 | 是。 | 是。 |
| `CTPL_DEMO1.1.8/app/src/main/java/com/ctaiot/ctprinter/ctpl_demo/*.java` | `/tmp/chiteng-sdk-analysis/Android-SDK 开发包/CTPL_DEMO1.1.8/app/src/main/java/com/ctaiot/ctprinter/ctpl_demo/` | 10 Java files, 2,883 lines | Demo 辅助类: `ApiException`, `BinModel`, `DensityUtil`, `MainActivity`, `RespObj`, `UpgradeActivity`, `UpgradeHttp` 等。 | 不需要。 | 不提交。 | 是。 | 是。 |
| `CTPL_DEMO1.1.8/app/apk/app-debug.apk` | `/tmp/chiteng-sdk-analysis/Android-SDK 开发包/CTPL_DEMO1.1.8/app/apk/app-debug.apk` | APK, 6.4 MB | 厂商 Demo APK。 | 不需要。 | 不能提交。 | 是。 | 是。 |
| `CTPL_DEMO1.1.8/app/src/main/res/**` | `/tmp/chiteng-sdk-analysis/Android-SDK 开发包/CTPL_DEMO1.1.8/app/src/main/res/` | Android resources | Demo UI、图片、示例资源。 | 不需要。 | 不提交。 | 是。 | 是。 |
| `CTPL_DEMO1.1.8/app/serial_port_communication.txt` | `/tmp/chiteng-sdk-analysis/Android-SDK 开发包/CTPL_DEMO1.1.8/app/serial_port_communication.txt` | TXT / C serial sample | 串口通信示例, 与 Clerk PDA 蓝牙 S1 接入无关。 | 不需要。 | 不提交。 | 是。 | 否。 |
| `CTPL_DEMO1.1.8/.gradle/**` | `/tmp/chiteng-sdk-analysis/Android-SDK 开发包/CTPL_DEMO1.1.8/.gradle/` | Gradle cache | Demo 构建缓存。 | 不需要。 | 不能提交。 | 是。 | 否。 |
| `CTPL_DEMO1.1.8/local.properties` | `/tmp/chiteng-sdk-analysis/Android-SDK 开发包/CTPL_DEMO1.1.8/local.properties` | Local SDK path config | 开发者本机路径。 | 不需要。 | 不能提交。 | 是。 | 否。 |
| `CTPL_DEMO1.1.8/gradle/wrapper/**`, `gradlew`, `gradlew.bat` | `/tmp/chiteng-sdk-analysis/Android-SDK 开发包/CTPL_DEMO1.1.8/` | Gradle wrapper | Demo 自带 wrapper。Direct Loop Android 已有自己的 Gradle 工程。 | 不需要。 | 不提交。 | 是。 | 否。 |

No `.aar` or native `.so` file was found in the SDK bundle. The runtime SDK
artifact is the single JAR `ctaiotCtpl1.1.8.jar`.

## SDK Findings

### 20-Point Integration Matrix

| 问题 | 结论 |
|---|---|
| 1. 是否推荐走官方 Android SDK | 推荐。当前通用 TSPL 能走纸但无内容, 说明 socket 通路可用, 但命令模式、字体、纸张或私有协议不匹配。官方 CTPL SDK 明确封装了标签尺寸、纸张模式、文字、条码、状态查询和连续打印。 |
| 2. 是否有 AAR / JAR 可以直接放进 `app/libs` | 有 JAR: `ctaiotCtpl1.1.8.jar`。未发现 AAR。 |
| 3. 是否需要 Gradle 配置 | 需要。建议在 `app/build.gradle.kts` 里显式加 `implementation(files("libs/ctaiotCtpl1.1.8.jar"))`, 不用通配把未知 JAR/AAR 全部引入。 |
| 4. 是否需要初始化 SDK | 需要。`CTPL.getInstance().init(Application, RespCallback)` 应在 Android app 启动或 printer service 初始化时执行一次。 |
| 5. 是否支持 Bluetooth Classic SPP | 支持。`CTPL.Port.SPP`, connection success code `257`。 |
| 6. 是否支持 BLE | 支持。`CTPL.Port.BLE`, connection success code `256`; Demo 使用 BLE service UUID `49535343-fe7d-4ae5-8fa9-9fafd205e455`。 |
| 7. 是否有搜索打印机方法 | SDK JAR 没看到公开 discovery API。Demo 用 Android 系统 API 做 SPP discovery 和 BLE scan。Direct Loop 现有 `startPrinterDiscovery()` 可继续复用。 |
| 8. 是否有连接打印机方法 | 有。`CTPL.getInstance().connect(Device)`。 |
| 9. 是否有断开连接方法 | 有。`CTPL.getInstance().disconnect()`。 |
| 10. 是否有打印文字方法 | 有。`drawText(...)`。 |
| 11. 是否有打印条码方法 | 有。`drawBarCode(...)`, 支持 `CODE_128` 等。 |
| 12. 是否有打印二维码方法 | 有。`drawQRCode(...)`。 |
| 13. 是否有打印图片方法 | 有。`drawBitmap(...)`, `drawBitmapSplit(...)`。 |
| 14. 是否有设置纸张宽高的方法 | 有。`setSize(widthMm, heightMm)`。手册和 Demo 均要求先设置尺寸。 |
| 15. 是否有设置 GAP / 黑标 / 连续纸的方法 | 有。`setPrintMode(PrintMode)` 支持 `Label_Divide`, `Recepit_Continuous`, `Label_Continuous`, `Label_BlackMark`; `setPaperType(PaperType)` 支持 `Label`, `Receipt`, `BlackMark`。 |
| 16. 是否有连续打印方法 | 有。Demo 有 "连续打印多张", 使用 `setBackpressure(true)` 并循环 `print(1).execute()`。`print(count)` 也支持同一标签多份。 |
| 17. 是否有打印状态回调 | 有。`RespCallback.onDataResponse(HashMap<String, String>)` 接收查询结果; 连接结果走 `onConnectRespsonse`。 |
| 18. 是否支持 60x40 | 支持。SDK 使用 mm 尺寸, `setSize(60, 40)`。 |
| 19. 是否支持 40x30 | 支持。SDK guide / Demo 出现 `setSize(40, 30)` 示例。 |
| 20. 是否支持打印后状态确认 | 部分支持。可用 `queryPrintState()`, `queryPaperType()`, `queryBattery()`, `queryPrintMode()` 等查询状态, 但蓝牙标签打印是否能精确确认每张标签落纸成功需要实机验证。生产回写不能只等同于 socket write success。 |

### SDK Packaging

The official SDK bundle contains a JAR, not an AAR:

```text
CTPL_DEMO1.1.8/app/libs/ctaiotCtpl1.1.8.jar
```

The SDK guide references an older filename:

```text
ctaiotCtpl1.0.0.jar
```

The actual provided artifact is:

```text
ctaiotCtpl1.1.8.jar
```

Current Gradle dependency:

```kotlin
implementation(files("libs/ctaiotCtpl1.1.8.jar"))
```

Do not use a broad `fileTree` unless there is a real need to import multiple
vendor artifacts. A narrow explicit dependency keeps review scope clear.

### SDK Initialization

The SDK uses a singleton:

```java
CTPL.getInstance().init(application, new RespCallback() {
    @Override
    public void onConnectRespsonse(int port, int reason) {
    }

    @Override
    public void onDataResponse(HashMap<String, String> result) {
    }

    @Override
    public boolean autoSPPBond() {
        return false;
    }
});
```

Direct Loop should keep `autoSPPBond()` false at first. The current product
policy is: show discovered devices, allow connect only when bonded, and ask the
operator to pair from Android system Bluetooth when needed.

### Bluetooth Connection

The SDK supports:

```text
CTPL.Port.SPP
CTPL.Port.BLE
CTPL.Port.WIFI
CTPL.Port.USB
CTPL.Port.COM
```

For Chiteng S1 PDA work, use Bluetooth in this order:

1. Official SDK over SPP for paired S1 printers.
2. Official SDK over BLE only if SPP fails or vendor confirms S1 BLE mode is the intended route.
3. Existing raw Bluetooth Classic SPP bridge only for diagnostics and fallback.

The Demo SPP/BLE connection pattern:

```java
Device d = new Device();
CTPL.Port port = "SPP".equals(bluetoothType) ? CTPL.Port.SPP : CTPL.Port.BLE;
d.setPort(port);
d.setBluetoothMacAddr(bluetoothMac);
if (port == CTPL.Port.BLE) {
    d.setBleServiceUUID("49535343-fe7d-4ae5-8fa9-9fafd205e455");
}
CTPL.getInstance().connect(d);
```

Connection result codes from the SDK guide:

| Code | Meaning |
|---:|---|
| `256` | BLE connected |
| `257` | SPP connected |
| `258` | USB connected |
| `512` | Unsupported connection type |
| `513` | Duplicate connect while connecting |
| `514` | Duplicate connect while already connected |
| `515` | Invalid input |
| `516` | Insufficient permission |
| `517` | Phone system error |
| `518` | SPP pairing failed |
| `519` | BLE service mismatch |

### Discovery / Search

The SDK JAR does not expose a public printer discovery method. The vendor Demo
implements search in application code:

- SPP search: `BluetoothAdapter.startDiscovery()` and
  `BluetoothDevice.ACTION_FOUND`.
- BLE search: `BluetoothLeScanner.startScan(...)`.
- SPP filter: `DEVICE_TYPE_CLASSIC` or `DEVICE_TYPE_DUAL`.
- BLE filter: `DEVICE_TYPE_LE` or `DEVICE_TYPE_DUAL`.

Direct Loop already has the right high-level bridge shape:

```text
startPrinterDiscovery()
stopPrinterDiscovery()
getDiscoveredPrinters()
getPrinterStatus()
```

The next SDK PR should keep the existing discovery bridge and use the official
SDK only for Chiteng S1 connection and printing.

### Print APIs

Useful public CTPL APIs identified from the SDK/Demo:

```text
clean()
setSize(widthMm, heightMm)
setPrintSpeed(speed)
setPrintDensity(density)
setPrintMode(PrintMode)
setPaperType(PaperType)
setOrientation(Direction, Mirror)
setShift(xDots, yDots)
setFormFeed()
setReverse(Rect)
drawText(Point, Rotate, xScale, yScale, text)
drawBarCode(Point, heightDots, BarCode, Paint.Align, Rotate, narrow, wide, content)
drawQRCode(Point, QRLevel, cellWidth, QREncodeMode, content)
drawBitmap(Rect, Bitmap, isCompressed, ditherLimit)
drawBitmapSplit(Rect, Bitmap, isCompressed, hasNext, ditherLimit)
append(byte[])
print(count)
execute()
setBackpressure(boolean)
```

For `STORE_ITEM` labels, prefer SDK vector commands first:

1. `setSize(...)`
2. `setPrintMode(Label_Divide)` for gap labels
3. `setPaperType(Label)`
4. `setPrintDensity(...)`
5. `drawText(...)`
6. `drawBarCode(..., CODE_128, ...)`
7. `print(1)`
8. `execute()`

Use `drawBitmap(...)` only if SDK text/barcode layout cannot produce stable
scan results or if Chinese fonts / exact preview rendering must match the web
preview pixel-for-pixel.

### Status APIs

The SDK exposes query methods:

```text
queryPrintState()
queryHardwareConfig()
queryHardwareModel()
queryCompressPrint()
queryFirmwareInfo()
queryPaperType()
queryMemoryPrint()
queryDensity()
querySpeed()
queryAutoShutdown()
queryDisplayInfo()
queryHardwareVersion()
queryBinaryInfo()
queryBattery()
queryPrintMode()
queryVidAndPid()
selfTest()
```

Documented callback keys include:

```text
DeviceCover
DevicePause
DeviceIdle
DeviceOverHeat
CommandMode
Density
Speed
Battery
MemoryPrint
SmartVelocity
PaperType
AutoShutdownMinute
PrintDirection
Dpi
PrintAlignment
PrintMethod
HardwareVersion
PrintMode
SupportCompress
DeviceCutter
DevicePrintMaxWidthMM
DevicePrintMaxHeightMM
PaperReady
CarbonReady
GapHeightMM
FeedOffsetMM
PrintOffsetYMM
PrintOffsetXMM
CalibrationMM
FirmwareVersion
```

Production status design should distinguish:

- bytes accepted by SDK / socket
- SDK execute returned without exception
- printer state query says idle / paper ready
- business print job marked printed

Only the last one is a FW-ERP business state and must not be set by diagnostic
or preview methods.

### TSPL Manual Findings

The Chiteng TSPL manual is important because it explains why generic TSPL can
feed paper but print no content:

- Chiteng TSPL is not full standard TSPL. It supports only common commands and
  adds private optimizations.
- Each command must end with `\r\n` (`0x0D 0x0A`). Commands without CRLF may not
  execute correctly.
- The quick-start sequence is minimal: `SIZE`, `TEXT`, `PRINT`.
- `SIZE` must be sent before label content.
- Documented text fonts include `TSS16.BF2` and `TSS24.BF2`.
- The manual notes most machines only support `TSS24.BF2`.
- `BITMAP` command modes `3` and `4` are Chiteng private compressed / long
  bitmap modes. Do not hand-roll these for production unless the SDK path fails.

Better raw TSPL diagnostic payload for S1:

```text
SIZE 60 mm,40 mm\r\n
CLS\r\n
TEXT 20,20,"TSS24.BF2",0,1,1,"DIRECT LOOP"\r\n
TEXT 20,60,"TSS24.BF2",0,1,1,"S1 TEST"\r\n
PRINT 1,1\r\n
```

This should replace generic font `"0"` as the main raw TSPL diagnostic, but
official SDK remains the recommended integration path.

### ESC Manual Findings

The ESC manual is for Chiteng receipt printers and ESC/POS-style behavior. It
is useful for:

- text-only socket diagnostics
- receipt-mode proof of write
- status command reference

It is not the best production path for 60x40 or 40x30 adhesive label printing,
because the STORE_ITEM requirement needs controlled label dimensions, gap
handling, Code128 barcode placement, and repeatable batch printing.

## Recommended Integration Path

### Recommendation A: Official SDK

Use official CTPL SDK for Chiteng S1.

Reasons:

- Existing Direct Loop raw TSPL already proves Bluetooth socket writes reach the
  printer because S1 feeds paper.
- Blank content suggests command/font/media/private protocol mismatch, not a
  WebView bridge problem.
- CTPL SDK directly supports the needed label primitives:
  `setSize`, paper mode, text, Code128 barcode, QR, bitmap, print count,
  continuous printing, and status queries.
- SDK supports both SPP and BLE, which covers the likely S1 transport modes.
- SDK gives a safer path for 60x40 and 40x30 labels than guessing generic TSPL.

### Backup B: Official Chiteng TSPL

Keep official TSPL as a fallback and diagnostic route.

Use Chiteng-documented fonts and CRLF endings. Avoid generic TSPL assumptions
such as font `"0"`, unsupported `GAPDETECT`, unsupported `SET TEAR`, or commands
not listed in the Chiteng manual except as explicit diagnostics.

### Backup C: ESC

Keep ESC only for receipt/text diagnostics and maybe raw feed proof. Do not make
ESC the primary path for STORE_ITEM labels.

### Not Recommended: Launch Native Chiteng App For Manual Printing

Do not rely on invoking the vendor app manually:

- It breaks the Clerk PDA workflow.
- It cannot safely enforce POS barcode rules.
- It cannot reliably return per-label print result to FW-ERP.
- It makes batch printing and贴标确认 hard to audit.

## Existing DirectLoopPdaPrinterBridge Comparison

Existing bridge methods:

```text
getPrinterStatus()
listPairedPrinters()
connectPrinter()
disconnectPrinter()
printTestLabel()
getLastPrintResult()
startPrinterDiscovery()
stopPrinterDiscovery()
getDiscoveredPrinters()
```

Reusable pieces:

- WebView trusted-host gate for `FW_ERP_HOST`.
- Paired printer listing.
- Discovery state and dedupe by Bluetooth address.
- Selected printer/profile state.
- Status JSON shape.
- JS bridge object name: `window.DirectLoopPdaPrinter`.
- Error handling policy: never crash WebView.

Second-stage implementation status:

- Added an SDK-backed Chiteng diagnostic implementation behind
  `CHITENG_S1_OFFICIAL`.
- Keep current raw SPP implementation as `CHITENG_S1_RAW_DIAGNOSTIC` or as the
  existing diagnostic protocol variants.
- Connect Chiteng official profile through `CTPL.connect(Device)`.
- Print Chiteng official diagnostic labels through `CTPL.drawText` /
  `CTPL.drawBarCode`.
- Keep SDK calls isolated in `ChitengS1OfficialPrinterClient.kt`.
- Status truthfulness now uses `CTPL.queryPrintState()` plus
  `RespCallback.onDataResponse(...)` as a non-printing health check for
  `CHITENG_S1_OFFICIAL`.
- A previous SDK connect success is not treated as proof that the printer is
  still online. If `queryPrintState()` times out or throws, Android reports the
  selected printer as disconnected/offline and asks the user to turn on and
  reconnect the printer.
- Paired and discovered printer rows now carry `source` and `online_status`.
  `source=paired` means Android has a bond; `source=discovered` means Bluetooth
  discovery saw the device. Neither is equivalent to online or connected.

Raw TSPL tests to keep:

- `RAW_LF_FEED`
- official-font `TSPL_SIMPLE_TEXT`
- `TSPL_DENSITY_TEXT`
- `TSPL_NO_GAP_CONTINUOUS`

Raw TSPL tests to downgrade to "diagnostic only":

- `TSPL_GAP_DETECT`
- generic TSPL font `"0"` variants
- unsupported `SET TEAR` / `REFERENCE` if not documented by Chiteng

## Bridge Design

The next bridge work should add SDK/preview methods without making them
production print completion APIs.

Current truthful-status bridge fields:

```json
{
  "connection_status": "connected | disconnected | connecting | error",
  "printer_online_status": "online | offline | unknown | error",
  "printer_health_checked_at": "yyyy-MM-dd HH:mm:ss",
  "official_sdk_available": true,
  "official_sdk_connected": false,
  "official_sdk_last_message": "",
  "official_sdk_last_error": ""
}
```

For `CHITENG_S1_OFFICIAL`, `getPrinterStatus()` may run the CTPL status query.
This is intentionally separate from printing: no test label, feed command,
calibration command, or STORE_ITEM payload is used as a health check.

### Current Diagnostic: `printTestLabel("CHITENG_S1_OFFICIAL")`

Second-stage Android implementation adds one official SDK diagnostic protocol:

```text
window.DirectLoopPdaPrinter.printTestLabel("CHITENG_S1_OFFICIAL")
```

Behavior:

- Uses `app/libs/ctaiotCtpl1.1.8.jar`.
- Routes through `ChitengS1OfficialPrinterClient.kt`, not raw TSPL/CPCL/ESC.
- Uses the selected bonded Bluetooth printer address.
- Uses `CTPL.Port.SPP` first.
- Prints one 60x40 diagnostic label containing:
  - `DIRECT LOOP`
  - `CHITENG S1 OFFICIAL`
  - `SDK TEST`
  - `KES 450`
  - `TEST123456`
  - Code128 barcode for `TEST123456`
- Sets `last_protocol_tested = "CHITENG_S1_OFFICIAL"`.
- Sets `last_print_result = "success"` only when the SDK connect/print/execute
  path returns without exception.
- Does not generate STORE_ITEM barcode.
- Does not call FW-ERP backend.
- Does not mark any print job as printed.

### `printChitengTestLabel(jsonPayload)`

Purpose: print one official SDK / official command diagnostic label.

Input sketch:

```json
{
  "printer_profile": "CHITENG_S1_OFFICIAL",
  "transport": "SPP",
  "address": "S1-3696-MAC",
  "label_template_size": "60x40",
  "label_width_mm": 60,
  "label_height_mm": 40,
  "test_protocol": "official_sdk",
  "include_barcode": true,
  "barcode_value": "5261290000014"
}
```

Expected behavior:

- Use selected bonded printer.
- Use CTPL SDK when `printer_profile` is `CHITENG_S1_OFFICIAL`.
- Print "DIRECT LOOP", "S1 SDK TEST", current timestamp, and optional Code128.
- Record last protocol exactly, for example `official_sdk_chiteng_s1_60x40`.
- Return success only when SDK write/execute succeeds.
- Do not mark any business print job as printed.

### `previewStoreItemLabel(jsonPayload)`

Purpose: validate and normalize label preview data without printing.

Expected behavior:

- Parse FW-ERP-provided payload.
- Validate `label_template_size`, dimensions, and labels.
- Confirm every label has a `machine_code` / `barcode_value`.
- Return normalized preview fields and warnings, such as truncated category or
  omitted source field on 40x30.
- Do not call printer APIs.
- Do not generate business barcode.

### `printStoreItemLabelPreview(jsonPayload)`

Purpose: print one real-looking preview label for the selected batch, without
marking any real print job printed.

Expected behavior:

- Exposed to FW-ERP as `printStoreItemLabelPreview(payloadJson)`.
- Print exactly one STORE_ITEM preview label from the payload.
- Reject the payload with `Preview print only supports exactly one STORE_ITEM label.`
  when `labels.length` is not exactly 1.
- Support only 60x40 and 40x30 gap labels.
- Require `printer_profile=CHITENG_S1_OFFICIAL`.
- Requirement phrase for review: machine_code must be numeric and start with 5.
- Require `barcode_value` to equal `machine_code`.
- Use Code128 with barcode value equal to the STORE_ITEM `machine_code`.
- Return SDK/transport result and printer status.
- Do not change FW-ERP business print status.
- Do not generate STORE_ITEM barcodes in Android.
- Do not expose batch printing in this PR.

### `printStoreItemBatchDiagnostic(jsonPayload)`

Purpose: test continuous printing using real payload shape without business
writeback.

Expected behavior:

- Print a bounded number of labels from payload, for example max 3 or max 5
  unless manually increased in debug UI.
- Return per-label write result:
  `label_index`, `machine_code`, `sdk_result`, `error`.
- Do not set `print_status=printed` in FW-ERP.
- Do not call any backend writeback endpoint.

### Later Production Method: `printStoreItemBatch(jsonPayload)`

Purpose: formal batch printing after diagnostic preview is proven.

Expected behavior in a later PR only:

- Print all labels in a batch.
- Return per-label results.
- Support retry of failed label index range.
- Only FW-ERP web/backend should decide when to persist print status or enable
 贴标确认. Android should not independently mutate business state.

## Label Templates

All dimensions below assume 203 dpi, where 1 mm is approximately 8 dots. If a
specific S1 model reports 300 dpi, coordinates must be scaled after querying or
configuring DPI.

Use Code128 for `STORE_ITEM machine_code`. The POS scanner must scan
`STORE_ITEM`, not `SDP`, `SDO`, `SDB`, `LPK`, or `RAW_BALE`.

Android must print the barcode value provided by FW-ERP:

```text
label.barcode_value || label.machine_code
```

Android must not generate, transform, or infer business barcode values.

For the PR #19 one-label preview bridge, the physical customer label must only
print:

- `category_short`
- `grade` or `pricing_type`
- `price_kes`
- one Code128 barcode
- barcode value = `STORE_ITEM machine_code`
- `machine_code` text below the barcode

The preview label must not print SDO, SDP, SDB, LPK, `transfer_no`,
`pricing_batch_id`, `source_sdp`, `store_code`, `display_code`, QR code,
multiple barcodes, or any internal source chain.

### 60x40 Standard Label

Use case:

- standard clothing item labels
- full item traceability
- price + category + grade + source SDP

Physical size:

```text
60 mm x 40 mm
approx 480 dots x 320 dots at 203 dpi
```

Priority:

1. Barcode scannability.
2. Clear price.
3. Traceability to source SDP.
4. Clerk-readable category and pricing type.

Recommended fields:

| Field | Required | Notes |
|---|---|---|
| `STORE_ITEM machine_code` barcode | Yes | Large Code128 barcode. This is the POS-scannable product code. |
| `machine_code` text | Yes | Human-readable below barcode. |
| `price_kes` | Yes | Large, clear `KES xxx`. |
| `display_code` | Yes if space allows | Small human reference. |
| `category_main` / `category_sub` | Yes | Truncate to fit. |
| `grade` / `pricing_type` | Yes | `P`, `S`, or `CUSTOM`. |
| `store_code` | Yes | Example: `UTAWALA`. |
| `source_sdp_display_code` | Yes | Traceability only, not POS barcode. |
| `pricing_batch_id` | Yes | Example: `BATCH-01-P`. |

ASCII sketch:

```text
+----------------------------------------------------------+
| DIRECT LOOP / FW-ERP                 KES 450             |
|                                                          |
|   ||||||||||||||||||||||||||||||||||||||||||||||||||     |
|   |||||||||||||| STORE_ITEM machine_code |||||||||||     |
|                                                          |
|   5261290000014                                         |
|   STOREITEM26129000001                                  |
|                                                          |
|   cargo pant  P                         UTAWALA         |
|   SDP261290019                       BATCH-01-P         |
+----------------------------------------------------------+
```

Coordinate suggestion at 203 dpi:

| Element | X | Y | Size |
|---|---:|---:|---|
| Header `DIRECT LOOP / FW-ERP` | 16 | 12 | SDK text scale 1, or `TSS24.BF2` |
| Price `KES 450` | 330 | 12 | SDK text scale 2 where available; otherwise bitmap text |
| Code128 barcode | 30 | 58 | height 92 dots, narrow 2, wide 2 or SDK default tuned by test |
| Machine code text | 30 | 158 | SDK text scale 1 |
| Display code | 30 | 186 | SDK text scale 1 |
| Category + grade | 30 | 222 | SDK text scale 1 |
| Store code | 330 | 222 | SDK text scale 1 |
| Source SDP | 30 | 258 | SDK text scale 1 |
| Batch id | 310 | 258 | SDK text scale 1 |

Barcode recommendation:

- Type: Code128.
- Rotation: no rotation.
- Minimum quiet zone: leave at least 20 dots left/right if possible.
- Barcode height: start with 90 to 100 dots.
- Print density: start with SDK density 10 to 12, tune on real paper.
- Speed: start low, for example SDK speed 2, for better barcode quality.

Paper / gap recommendation:

- SDK: `setSize(60, 40)`.
- SDK: `setPaperType(Label)`.
- SDK: `setPrintMode(Label_Divide)` for gap labels.
- Raw TSPL fallback: `SIZE 60 mm,40 mm`, then official Chiteng text/barcode
  commands with CRLF line endings.

SDK command sketch:

```text
clean()
setBackpressure(true)
setSize(60, 40)
setPaperType(Label)
setPrintMode(Label_Divide)
setPrintDensity(10 or 12)
setPrintSpeed(2)
drawText(header)
drawText(price)
drawBarCode(CODE_128, barcode_value)
drawText(machine_code)
drawText(display_code)
drawText(category + grade)
drawText(store_code)
drawText(source_sdp)
drawText(pricing_batch_id)
print(1)
execute()
```

### 40x30 Small Label

Use case:

- small items
- accessories
- shoes and bags
- low-price items
- labels where source traceability must be minimized for space

Physical size:

```text
40 mm x 30 mm
approx 320 dots x 240 dots at 203 dpi
```

Priority:

1. Barcode scannability.
2. Clear price.
3. Short category / grade.

Required fields:

| Field | Required | Notes |
|---|---|---|
| `STORE_ITEM machine_code` barcode | Yes | Must remain the largest machine-readable element. |
| `machine_code` text | Yes | Human-readable, small. |
| `price_kes` | Yes | Large enough for clerk/customer. |
| `category_short` | Yes | Example: `cargo`, `jacket`, `shoes`. |
| `grade` / `pricing_type` | Yes | `P`, `S`, or `C`. |

Optional / omitted fields:

| Field | Decision |
|---|---|
| `display_code` | Optional; omit when barcode or price becomes cramped. |
| full `source_sdp` | Omit by default. If required, use very short suffix only. |
| full category chain | Omit; use `category_short`. |
| store code | Optional short text, omit if scan tests are poor. |
| batch id | Omit from physical label; keep in print batch record. |

ASCII sketch:

```text
+--------------------------------------+
| cargo P                 KES 450      |
|                                      |
|  ||||||||||||||||||||||||||||||||    |
|  || STORE_ITEM machine_code ||||     |
|                                      |
|  5261290000014                      |
|  STOREITEM...00001   UTAWALA        |
+--------------------------------------+
```

Coordinate suggestion at 203 dpi:

| Element | X | Y | Size |
|---|---:|---:|---|
| `category_short grade` | 12 | 10 | SDK text scale 1 |
| Price `KES 450` | 200 | 10 | SDK text scale 1 or 2 after fit test |
| Code128 barcode | 20 | 54 | height 72 to 82 dots |
| Machine code text | 20 | 138 | SDK text scale 1 |
| Short display/store text | 20 | 168 | optional, small |

Barcode recommendation:

- Type: Code128.
- Rotation: no rotation initially.
- Barcode height: start with 75 dots.
- Width: keep quiet zones and reduce other text before shrinking barcode.
- Do not add QR code on 40x30 unless a later real scan test proves enough space.

Paper / gap recommendation:

- SDK: `setSize(40, 30)`.
- SDK: `setPaperType(Label)`.
- SDK: `setPrintMode(Label_Divide)` for gap labels.
- If labels are continuous paper, use a separate explicit printer profile /
  template mode and do not silently switch from gap labels.

## Store Item Label Preview

The real preview page belongs in FW-ERP web, not Android. Android should only
print the payload it receives and report printer state/results.

Future FW-ERP Clerk PDA location:

```text
店员端完成分批定价并生成 STORE_ITEM
-> 打印本批标签
-> 选择标签模板 60x40 / 40x30
-> 显示真实打印预览
-> 点击 打印本批
-> Android 执行打印
-> 打印成功后允许 贴标确认
```

The page should be Chinese-first, button-heavy, and low-text. It should use
real batch data, not demo placeholders.

### Page State

Top summary card:

| Field | Example |
|---|---|
| 当前 SDP | `SDP261290019` |
| 当前批次 | `BATCH-01-P` |
| 商品数量 | `24 件` |
| 标签模板 | `60x40 标准标签` or `40x30 小标签` |
| 预计打印张数 | `24 张` |
| 打印机 | `S1-3696 已连接` or `未连接` |

Middle preview card:

- Render one visual label preview using the first real label in the batch.
- Show real `STORE_ITEM machine_code` as barcode value.
- Show price, category, grade/pricing type, store code, and source SDP according
  to the selected template.
- If a field will be truncated or omitted on 40x30, show the preview exactly
  that way.

Bottom actions:

| Button | Behavior |
|---|---|
| `选择 60x40` | Saves template selection to current pricing batch / print batch. |
| `选择 40x30` | Saves template selection to current pricing batch / print batch. |
| `打印本批标签` | Disabled unless printer connected and labels exist. Later calls Android bridge. |
| `返回分批定价` | Return to pricing batch screen without clearing task state. |
| `贴标确认` | Disabled until print succeeds. Not enabled by preview-only diagnostics. |

Printer-not-connected behavior:

```text
打印机未连接，请先到 我的 -> 打印机连接 连接蓝牙打印机。
```

Do not reload the Clerk PDA page, reset assigned SDP task polling, or clear PDA
state when entering or leaving the preview page.

### Preview Data Rules

- Template selection is saved per pricing batch / print batch, not globally.
- A batch can choose 60x40 while another chooses 40x30.
- The preview should use the same payload contract that Android will later
  print.
- The preview should not generate a barcode. It displays the barcode value
  already generated by FW-ERP backend.

Print batch fields to persist later:

```text
label_template_size: "60x40" | "40x30"
label_width_mm
label_height_mm
printer_profile: "CHITENG_S1"
print_protocol: "official_sdk" | "official_tspl" | "esc"
pricing_batch_id
source_sdp_display_code
source_sdp_machine_code
store_code
printed_by
print_status
```

## Print Payload Contract

Android must not generate business barcode values. Android only prints the
FW-ERP-provided payload.

Android must not:

- create `STORE_ITEM`
- transform `SDP` / `SDB` / `LPK` / `SDO` into product codes
- change POS scan rules
- change barcode resolver behavior
- mark print jobs as printed from diagnostic/preview APIs

Example WebView to Android payload:

```json
{
  "printer_profile": "CHITENG_S1",
  "label_template_size": "60x40",
  "label_width_mm": 60,
  "label_height_mm": 40,
  "print_mode": "diagnostic_preview",
  "store_code": "UTAWALA",
  "source_sdp_display_code": "SDP261290019",
  "source_sdp_machine_code": "SDP261290019",
  "pricing_batch_id": "BATCH-01-P",
  "labels": [
    {
      "display_code": "STOREITEM26129000001",
      "machine_code": "5261290000014",
      "barcode_value": "5261290000014",
      "price_kes": 450,
      "category_main": "pants",
      "category_sub": "cargo pant",
      "category_short": "cargo",
      "grade": "P",
      "pricing_type": "P",
      "store_code": "UTAWALA",
      "source_sdp": "SDP261290019"
    }
  ]
}
```

Recommended validation:

| Field | Rule |
|---|---|
| `printer_profile` | For S1 production path, must be `CHITENG_S1` or later `CHITENG_S1_OFFICIAL`. |
| `label_template_size` | Must be `60x40` or `40x30`. |
| `label_width_mm` / `label_height_mm` | Must match template. Reject inconsistent values. |
| `print_mode` | Diagnostic APIs accept `diagnostic_preview`; production API later requires `production`. |
| `labels` | Non-empty for printing. Preview API may accept exactly one label. |
| `machine_code` | Required. Must be a STORE_ITEM product code. |
| `barcode_value` | Required or defaults to `machine_code`. Must not be SDP/SDB/LPK/SDO. |
| `price_kes` | Required for customer-visible label. |
| `pricing_batch_id` | Required for audit and retry grouping. |

Recommended Android result shape:

```json
{
  "ok": true,
  "printer_profile": "CHITENG_S1_OFFICIAL",
  "print_protocol": "official_sdk",
  "label_template_size": "60x40",
  "print_mode": "diagnostic_preview",
  "attempted_label_count": 1,
  "printed_label_count": 1,
  "results": [
    {
      "index": 0,
      "display_code": "STOREITEM26129000001",
      "machine_code": "5261290000014",
      "write_status": "success",
      "printer_status": "unknown"
    }
  ],
  "last_error": ""
}
```

For diagnostics, `printed_label_count` means accepted by SDK/write path, not
business-confirmed production print.

## Android Change Plan

Future Android PR files likely affected:

| File | Change |
|---|---|
| `app/build.gradle.kts` | Added explicit `implementation(files("libs/ctaiotCtpl1.1.8.jar"))` dependency after Eric approval. |
| `app/src/main/AndroidManifest.xml` | Confirm Bluetooth permissions remain sufficient; avoid vendor demo storage/USB permissions unless needed. |
| `app/src/main/java/.../MainActivity.kt` | Initialize official SDK service if existing app startup pattern requires it. |
| `app/src/main/java/.../DirectLoopPdaPrinterBridge.kt` | Add new JS bridge methods and route Chiteng official profile to SDK service. |
| `app/src/main/java/.../BluetoothPrinterService.kt` or new `ChitengCtplPrinterService.kt` | Encapsulate SDK connection, status, and print calls. |
| `app/src/main/java/.../BluetoothPrinterTestPayloads.kt` | Keep raw diagnostics; add official SDK diagnostic layout if appropriate. |
| `docs/chiteng-s1-integration-notes.md` | Keep updated as implementation findings change. |

Permissions:

- Existing Android 9 discovery path already needs `BLUETOOTH`,
  `BLUETOOTH_ADMIN`, and location if discovery is used.
- Android 12+ guarded permissions remain `BLUETOOTH_CONNECT` and
  `BLUETOOTH_SCAN`.
- Do not copy vendor demo's broad storage, manage external storage, internet,
  or USB permissions unless a future feature proves they are required.

## FW-ERP Change Plan

Future FW-ERP PR B should be preview-only:

- Add Clerk PDA real print preview page after STORE_ITEM generation.
- Add 60x40 / 40x30 template selector.
- Persist selected template on pricing batch / print batch.
- Render real first-label preview from batch data.
- Show printer connection state from `window.DirectLoopPdaPrinter`.
- Disable print when bridge/printer unavailable.
- Do not call production print status writeback.
- Do not change POS scan rules or barcode resolver.

The official print payload must be produced by FW-ERP using backend-generated
STORE_ITEM codes.

## Rollout Plan

PR A: Android official SDK / official command diagnostic print.

- Add `ctaiotCtpl1.1.8.jar` after Eric confirmation.
- Add `CHITENG_S1_OFFICIAL` diagnostic route.
- Implement one SDK-backed 60x40 test label with text + Code128.
- Keep trusted-host gate.
- Do not implement production STORE_ITEM printing.

PR B: FW-ERP Clerk PDA real print preview page.

- Preview only.
- Use real generated STORE_ITEM data.
- Add 60x40 / 40x30 template selector.
- Do not call production print or mark printed.

PR C: Android `printStoreItemLabelPreview`.

- Accept the print payload contract.
- Print one preview label.
- Do not mark real print job printed.

PR D: Production `STORE_ITEM` batch continuous printing.

- Print each STORE_ITEM label in the batch.
- Return per-label results.
- Add retry design.
- Confirm how FW-ERP persists print success.

PR E: Clerk PDA贴标确认 and print status persistence.

- Enable confirmation only after accepted print result.
- Persist print batch status.
- Preserve POS rule: only STORE_ITEM is sale-scannable.

## Risks

| Risk | Mitigation |
|---|---|
| SDK授权不清 | Eric confirmed `ctaiotCtpl1.1.8.jar` may be committed. Continue not committing ZIP/PDF/Demo/APK unless separately approved. |
| 私有协议 | Prefer official SDK; keep official TSPL as fallback only. |
| Raw TSPL blank content | Use Chiteng fonts, CRLF, official SDK path. |
| 40x30 空间不足 | Prioritize barcode + price + short category/grade; omit source chain. |
| 条码可扫性 | Use Code128, enough quiet zone, low speed, tuned density, real POS scanner tests. |
| 连续打印中途失败 | Later production API must return per-label result and support retry by label index. |
| 打印状态不可靠 | Separate SDK write success, printer status query, and FW-ERP business confirmation. |
| 蓝牙断连 | Handle SDK connection callbacks, expose status, never crash WebView. |
| BLE/SPP mode差异 | Start with SDK SPP for paired S1; test BLE only if SPP remains problematic. |
| WebView安全 | Keep bridge calls restricted to configured `FW_ERP_HOST`. |
| Android permissions | Keep Android 9 and Android 12+ guarded permissions minimal and explicit. |

## Current Conclusion

1. Most recommended path: official CTPL Android SDK.
2. Required SDK artifact now added with Eric approval: `app/libs/ctaiotCtpl1.1.8.jar`.
3. Do not commit SDK ZIP, APK, Demo source, PDFs, `.gradle`, `local.properties`,
   build outputs, or secrets.
4. Do not commit SDK ZIP, PDFs, Demo source, APK, `.gradle`, `local.properties`,
   build outputs, or secrets.
5. 60x40 should be the standard STORE_ITEM label with Code128 barcode, large
   price, category/grade, store, source SDP, and batch.
6. 40x30 should be a compact label with Code128 barcode, price, short category,
   and grade; omit full source chain.
7. The next real preview page should be implemented in FW-ERP, because it owns
   STORE_ITEM data, template selection, pricing batch state, and barcode rules.
8. This PR is Android PR A: official SDK diagnostic label via
   `CHITENG_S1_OFFICIAL`.
