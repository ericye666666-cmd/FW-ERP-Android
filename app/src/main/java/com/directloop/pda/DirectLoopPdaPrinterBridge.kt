package com.directloop.pda

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class DirectLoopPrinterProfile {
    CHITENG_S1,
    CHITENG_S1_OFFICIAL,
    UROVO_K300,
    UROVO,
    GENERIC,
    ;

    companion object {
        fun from(input: String?): DirectLoopPrinterProfile {
            val normalized = input?.trim()?.uppercase(Locale.US).orEmpty()
            return entries.firstOrNull { it.name == normalized } ?: GENERIC
        }
    }
}

class DirectLoopPdaPrinterBridge(
    private val activity: Activity,
    private val isTrustedPage: () -> Boolean,
) {
    private var selectedPrinterName = ""
    private var selectedPrinterAddress = ""
    private var selectedProfile = DirectLoopPrinterProfile.GENERIC
    private var connectionStatus = STATUS_DISCONNECTED
    private var printerOnlineStatus = ONLINE_UNKNOWN
    private var printerHealthCheckedAt = ""
    private var officialSdkAvailable = false
    private var officialSdkConnected = false
    private var officialSdkLastMessage = ""
    private var officialSdkLastError = ""
    private var urovoPrinterAvailable = false
    private var urovoLastStatusCode: Int? = null
    private var urovoLastStatusText = ""
    private var k300SppAvailable = false
    private var k300SppLastCheckedAt = ""
    private var k300SppLastError = ""
    private var lastError = ""
    private var lastProtocolTested = ""
    private var lastPrintResult = RESULT_NONE
    private var lastPreviewTransport = ""
    private var lastPreviewLabelSize = ""
    private var lastPreviewTsplCommand = ""
    private var lastPreviewTsplLinesOverride: List<String>? = null
    private var lastPreviewTsplSentAt = ""
    private var lastPreviewTsplBytes = 0
    private var lastPreviewSdkOperations: List<String> = emptyList()
    private var previewPrintBusyUntilMs = 0L
    private var k300CpclPrintInProgress = false
    private var k300BatchLabelCount = 0
    private var k300BatchSentCount = 0
    private var k300BatchFailedCount = 0
    private var k300BatchStartedAt = ""
    private var k300BatchFinishedAt = ""
    private var k300BatchLastError = ""
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val chitengOfficialPrinterClient = ChitengS1OfficialPrinterClient(activity.application)
    private val urovoK300PrinterManagerClient = UrovoK300PrinterManagerClient()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val discoveredPrintersByAddress = linkedMapOf<String, DiscoveredPrinter>()
    private var discoveryStatus = DISCOVERY_IDLE
    private var discoveryReceiverRegistered = false
    private val discoveryTimeoutRunnable = Runnable {
        synchronized(this@DirectLoopPdaPrinterBridge) {
            cancelBluetoothDiscovery(bluetoothAdapter())
            finishPrinterDiscovery()
        }
    }
    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = bluetoothDeviceFromIntent(intent)
                    val rawRssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    val rssi = rawRssi.takeIf { it != Short.MIN_VALUE }?.toInt()
                    synchronized(this@DirectLoopPdaPrinterBridge) {
                        recordDiscoveredDevice(device, rssi)
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    synchronized(this@DirectLoopPdaPrinterBridge) {
                        finishPrinterDiscovery()
                    }
                }
            }
        }
    }

    @JavascriptInterface
    @Synchronized
    fun getAppInfo(): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        return JSONObject()
            .put("app_name", "Direct Loop PDA")
            .put("version_name", BuildConfig.VERSION_NAME)
            .put("version_code", BuildConfig.VERSION_CODE)
            .put("package_name", BuildConfig.APPLICATION_ID)
            .put("bridge_version", APP_INFO_BRIDGE_VERSION)
            .put("supported_methods", supportedAppInfoMethods())
            .toString()
    }

    @JavascriptInterface
    @Synchronized
    fun getPrinterStatus(): String {
        return guardedStatus().toString()
    }

    @JavascriptInterface
    @Synchronized
    fun listPairedPrinters(): String {
        return guardedStatus().toString()
    }

    @JavascriptInterface
    @Synchronized
    @SuppressLint("MissingPermission")
    fun startPrinterDiscovery(): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        val adapter = bluetoothAdapter()
            ?: return failDiscovery("Bluetooth adapter is not available on this device.").toString()
        if (!isBluetoothEnabled(adapter)) {
            return failDiscovery("Bluetooth is disabled. Enable Bluetooth before searching printers.").toString()
        }
        if (!ensureBluetoothDiscoveryPermission()) {
            return statusJson(bridgeAvailable = true, errorOverride = lastError).toString()
        }

        stopPrinterDiscoveryInternal(adapter, nextStatus = DISCOVERY_IDLE)
        discoveredPrintersByAddress.clear()
        seedPairedPrinters(adapter)
        discoveryStatus = DISCOVERY_SEARCHING
        lastError = ""

        return try {
            registerDiscoveryReceiver()
            if (!adapter.startDiscovery()) {
                stopPrinterDiscoveryInternal(adapter, nextStatus = DISCOVERY_ERROR)
                lastError = "Bluetooth printer discovery could not start. Retry from the PDA page or Android Bluetooth settings."
            } else {
                mainHandler.postDelayed(discoveryTimeoutRunnable, DISCOVERY_TIMEOUT_MS)
            }
            guardedStatus().toString()
        } catch (error: SecurityException) {
            stopPrinterDiscoveryInternal(adapter, nextStatus = DISCOVERY_ERROR)
            failDiscovery("Bluetooth permission denied while searching printers.").toString()
        } catch (error: RuntimeException) {
            stopPrinterDiscoveryInternal(adapter, nextStatus = DISCOVERY_ERROR)
            failDiscovery("Bluetooth printer discovery failed: ${error.message ?: "unknown error"}.").toString()
        }
    }

    @JavascriptInterface
    @Synchronized
    fun stopPrinterDiscovery(): String {
        if (!isTrustedPage()) return untrustedStatus().toString()
        stopPrinterDiscoveryInternal(bluetoothAdapter(), nextStatus = DISCOVERY_IDLE)
        return guardedStatus().toString()
    }

    @JavascriptInterface
    @Synchronized
    fun getDiscoveredPrinters(): String {
        return guardedStatus().toString()
    }

    @JavascriptInterface
    @Synchronized
    fun connectPrinter(configOrAddress: String): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        val selection = try {
            parsePrinterSelection(configOrAddress)
        } catch (_: JSONException) {
            return fail("connectPrinter expects a Bluetooth MAC address or JSON string with profile, address, and name.").toString()
        }
        if (selection.profile == DirectLoopPrinterProfile.UROVO_K300) {
            return connectExternalK300Spp(selection).toString()
        }
        if (selection.address.isBlank()) {
            return fail("connectPrinter requires a paired printer address or JSON config.").toString()
        }

        val adapter = bluetoothAdapter() ?: return fail("Bluetooth adapter is not available on this device.").toString()
        if (!ensureBluetoothConnectPermission()) return guardedStatus().toString()
        if (!isBluetoothEnabled(adapter)) return fail("Bluetooth is disabled. Enable Bluetooth before connecting a printer.").toString()
        if (!BluetoothAdapter.checkBluetoothAddress(selection.address)) {
            return fail("Invalid Bluetooth printer address: ${selection.address}.").toString()
        }

        val bondedDevice = bondedDeviceForAddress(adapter, selection.address)
            ?: return fail("请先在 Android 系统蓝牙中完成配对后再连接。").toString()

        selectedPrinterAddress = selection.address
        selectedPrinterName = selection.name
        if (selectedPrinterName.isBlank()) {
            selectedPrinterName = safeDeviceName(bondedDevice)
        }
        selectedProfile = selection.profile
        connectionStatus = STATUS_CONNECTING
        lastError = ""

        return try {
            if (selection.profile == DirectLoopPrinterProfile.CHITENG_S1_OFFICIAL) {
                closeSocket()
                val result = chitengOfficialPrinterClient.connect(selection.address, selectedPrinterName)
                if (!result.success) {
                    return fail(result.message).toString()
                }
            } else {
                chitengOfficialPrinterClient.disconnect()
                closeSocket()
                connectSocket(adapter, selection, bondedDevice)
            }
            connectionStatus = STATUS_CONNECTED
            printerOnlineStatus = ONLINE_UNKNOWN
            lastError = ""
            guardedStatus().toString()
        } catch (error: IllegalArgumentException) {
            closeSocket()
            fail("Invalid Bluetooth printer address: ${selection.address}.").toString()
        } catch (error: SecurityException) {
            closeSocket()
            fail("Bluetooth permission denied while connecting printer.").toString()
        } catch (error: IOException) {
            closeSocket()
            fail("Bluetooth printer connection failed: ${error.message ?: "unknown error"}.").toString()
        }
    }

    @JavascriptInterface
    @Synchronized
    fun disconnectPrinter(): String {
        if (!isTrustedPage()) return untrustedStatus().toString()
        chitengOfficialPrinterClient.disconnect()
        closeSocket()
        connectionStatus = STATUS_DISCONNECTED
        printerOnlineStatus = ONLINE_UNKNOWN
        printerHealthCheckedAt = timestamp()
        lastError = ""
        syncOfficialSdkSummary()
        return guardedStatus().toString()
    }

    @JavascriptInterface
    @Synchronized
    fun printTestLabel(protocol: String): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        val testProtocol = BluetoothPrinterTestProtocol.from(protocol)
            ?: return fail(
                "Unsupported printer test protocol: $protocol. Use ${BluetoothPrinterTestProtocol.supportedNames}.",
                printAttempt = true,
            ).toString()

        if (testProtocol == BluetoothPrinterTestProtocol.CHITENG_S1_OFFICIAL) {
            return printOfficialChitengTestLabel()
        }

        val stream = outputStream
            ?: return fail(
                "No Bluetooth printer is connected. Connect a paired printer before printing a test label.",
                printAttempt = true,
            ).toString()

        lastProtocolTested = testProtocol.name
        lastPrintResult = RESULT_FAILED

        return try {
            val payload = BluetoothPrinterTestPayloads.build(
                protocol = testProtocol,
                selectedProfile = selectedProfile.name,
                timestamp = timestamp(),
            )
            stream.write(payload)
            stream.flush()
            lastPrintResult = RESULT_SUCCESS
            lastError = ""
            guardedStatus().toString()
        } catch (error: IOException) {
            connectionStatus = STATUS_ERROR
            fail("Bluetooth test label failed: ${error.message ?: "unknown error"}.", printAttempt = true).toString()
        } catch (error: SecurityException) {
            connectionStatus = STATUS_ERROR
            fail("Bluetooth permission denied while printing test label.", printAttempt = true).toString()
        }
    }

    @JavascriptInterface
    @Synchronized
    fun printStoreItemLabelPreview(payloadJson: String): String {
        return printStoreItemLabelPreviewCtplNoLabelMode(payloadJson)
    }

    @JavascriptInterface
    @Synchronized
    fun printStoreItemLabelPreviewCtplNoLabelMode(payloadJson: String): String {
        return printStoreItemLabelPreviewWithProtocol(
            payloadJson = payloadJson,
            protocol = STORE_ITEM_LABEL_PREVIEW_CTPL_NO_LABEL_MODE_PROTOCOL,
            printer = ::printOfficialStoreItemLabelPreviewCtplNoLabelMode,
        )
    }

    @JavascriptInterface
    @Synchronized
    fun printStoreItemLabelPreviewCtplBitmapDemo(payloadJson: String): String {
        return printStoreItemLabelPreviewWithProtocol(
            payloadJson = payloadJson,
            protocol = STORE_ITEM_LABEL_PREVIEW_CTPL_BITMAP_DEMO_PROTOCOL,
            printer = ::printOfficialStoreItemLabelPreviewCtplBitmapDemo,
        )
    }

    @JavascriptInterface
    @Synchronized
    fun printStoreItemLabelPreviewRawTspl(payloadJson: String): String {
        return printStoreItemLabelPreviewWithProtocol(
            payloadJson = payloadJson,
            protocol = STORE_ITEM_LABEL_PREVIEW_TSPL_PROTOCOL,
            printer = ::sendRawStoreItemPreviewTspl,
        )
    }

    @JavascriptInterface
    @Synchronized
    fun printS1RawTsplMinText(): String {
        if (!isTrustedPage()) return untrustedStatus().toString()
        return sendOneShotRawS1DiagnosticTspl(
            protocol = S1_RAW_TSPL_MIN_TEXT_PROTOCOL,
            command = buildS1RawTsplMinTextCommand(),
        )
    }

    @JavascriptInterface
    @Synchronized
    fun printS1RawTsplBlackBox(): String {
        if (!isTrustedPage()) return untrustedStatus().toString()
        return sendOneShotRawS1DiagnosticTspl(
            protocol = S1_RAW_TSPL_BLACK_BOX_PROTOCOL,
            command = buildS1RawTsplBlackBoxCommand(),
        )
    }

    @JavascriptInterface
    @Synchronized
    fun getUrovoPrinterStatus(): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        selectedProfile = DirectLoopPrinterProfile.UROVO_K300
        val result = urovoK300PrinterManagerClient.getStatus()
        applyUrovoResult(result)
        lastPreviewTransport = PREVIEW_TRANSPORT_UROVO_PRINTER_MANAGER
        lastPreviewSdkOperations = result.operations
        lastError = if (result.success) "" else result.message
        return statusJson(bridgeAvailable = true, errorOverride = lastError, refreshHealth = false).toString()
    }

    @JavascriptInterface
    @Synchronized
    fun printUrovoK300MinText(): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        return printUrovoK300Diagnostic(
            protocol = UROVO_K300_MIN_TEXT_PROTOCOL,
            labelSize = "40x30",
            printer = { urovoK300PrinterManagerClient.printMinText() },
        )
    }

    @JavascriptInterface
    @Synchronized
    fun printUrovoK300BlackBox(): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        return printUrovoK300Diagnostic(
            protocol = UROVO_K300_BLACK_BOX_PROTOCOL,
            labelSize = "40x30",
            printer = { urovoK300PrinterManagerClient.printBlackBox() },
        )
    }

    @JavascriptInterface
    @Synchronized
    fun printUrovoK300StoreItemPreview(payloadJson: String): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        lastProtocolTested = UROVO_K300_STORE_ITEM_PREVIEW_PROTOCOL
        lastPrintResult = RESULT_FAILED

        val payload = try {
            UrovoK300PrinterManagerClient.StoreItemLabelPreviewPayload.fromJson(payloadJson)
        } catch (error: JSONException) {
            return previewPrintFailure("Urovo K300 STORE_ITEM preview payload is invalid: ${error.message ?: "invalid JSON"}.")
                .toString()
        } catch (error: IllegalArgumentException) {
            return previewPrintFailure("Urovo K300 STORE_ITEM preview payload is invalid: ${error.message ?: "invalid payload"}.")
                .toString()
        }

        return printUrovoK300Diagnostic(
            protocol = UROVO_K300_STORE_ITEM_PREVIEW_PROTOCOL,
            labelSize = payload.templateSize,
            printer = { urovoK300PrinterManagerClient.printStoreItemPreview(payload) },
        )
    }

    @JavascriptInterface
    @Synchronized
    fun printK300EscposMinText(): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        val text = "K300 ESC/POS TEST\n5261300000038\n\n"
        val bytes = byteArrayOf(0x1B, 0x40) + text.toByteArray(Charset.forName("GBK"))
        return sendOneShotK300SppDiagnostic(
            protocol = K300_ESCPOS_MIN_TEXT_PROTOCOL,
            command = "",
            bytes = bytes,
            operations = listOf(
                "open_spp_socket",
                "write_escpos_min_text",
                "flush",
                "close_spp_socket",
            ),
        )
    }

    @JavascriptInterface
    @Synchronized
    fun printK300CpclMinText(): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        val command = buildK300CpclMinTextCommand()
        return sendOneShotK300SppDiagnostic(
            protocol = K300_CPCL_MIN_TEXT_PROTOCOL,
            command = command,
            bytes = command.toByteArray(Charset.forName("GBK")),
            operations = listOf(
                "open_spp_socket",
                "write_cpcl_min_text",
                "flush",
                "close_spp_socket",
            ),
        )
    }

    @JavascriptInterface
    @Synchronized
    fun printK300CpclCode128Test(): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        return sendK300CpclCode128Variant(
            protocol = K300_CPCL_CODE128_TEST_PROTOCOL,
            command = buildK300CpclCode128TestCommand(),
            writeOperation = "write_cpcl_code128_test",
        )
    }

    @JavascriptInterface
    @Synchronized
    fun printK300CpclCode128WideTest(): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        return sendK300CpclCode128Variant(
            protocol = K300_CPCL_CODE128_WIDE_TEST_PROTOCOL,
            command = buildK300CpclCode128WideTestCommand(),
            writeOperation = "write_cpcl_code128_wide_test",
        )
    }

    @JavascriptInterface
    @Synchronized
    fun printK300CpclCode128TallTest(): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        return sendK300CpclCode128Variant(
            protocol = K300_CPCL_CODE128_TALL_TEST_PROTOCOL,
            command = buildK300CpclCode128TallTestCommand(),
            writeOperation = "write_cpcl_code128_tall_test",
        )
    }

    @JavascriptInterface
    @Synchronized
    fun printK300CpclCode128QuietZoneTest(): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        return sendK300CpclCode128Variant(
            protocol = K300_CPCL_CODE128_QUIET_ZONE_TEST_PROTOCOL,
            command = buildK300CpclCode128QuietZoneTestCommand(),
            writeOperation = "write_cpcl_code128_quiet_zone_test",
        )
    }

    @JavascriptInterface
    @Synchronized
    fun printK300CpclCode128CompactTopTest(): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        return sendK300CpclCode128Variant(
            protocol = K300_CPCL_CODE128_COMPACT_TOP_TEST_PROTOCOL,
            command = buildK300CpclCode128CompactTopTestCommand(),
            writeOperation = "write_cpcl_code128_compact_top_test",
        )
    }

    @JavascriptInterface
    @Synchronized
    fun printK300CpclRawPreview(payloadJson: String): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        lastProtocolTested = K300_CPCL_RAW_PREVIEW_PROTOCOL
        lastPrintResult = RESULT_FAILED

        val command = try {
            validateK300CpclRawPreviewPayload(payloadJson)
        } catch (error: JSONException) {
            return previewPrintFailure("K300 CPCL raw preview payload is invalid: ${error.message ?: "invalid JSON"}.")
                .toString()
        } catch (error: IllegalArgumentException) {
            return previewPrintFailure("K300 CPCL raw preview payload is invalid: ${error.message ?: "invalid payload"}.")
                .toString()
        }

        return sendOneShotK300SppDiagnostic(
            protocol = K300_CPCL_RAW_PREVIEW_PROTOCOL,
            command = command,
            bytes = command.toByteArray(Charset.forName("GBK")),
            operations = listOf(
                "open_spp_socket",
                "write_cpcl_raw_preview",
                "flush",
                "close_spp_socket",
            ),
            useK300CpclInFlightGuard = true,
            enforcePreviewBusyGuard = false,
        )
    }

    @JavascriptInterface
    @Synchronized
    fun printK300CpclRawBatch(payloadJson: String): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        lastProtocolTested = K300_CPCL_RAW_BATCH_PROTOCOL
        lastPrintResult = RESULT_FAILED

        val payload = try {
            validateK300CpclRawBatchPayload(payloadJson)
        } catch (error: JSONException) {
            return previewPrintFailure("K300 CPCL raw batch payload is invalid: ${error.message ?: "invalid JSON"}.")
                .toString()
        } catch (error: IllegalArgumentException) {
            return previewPrintFailure("K300 CPCL raw batch payload is invalid: ${error.message ?: "invalid payload"}.")
                .toString()
        }

        return sendK300CpclRawBatch(payload)
    }

    @JavascriptInterface
    @Synchronized
    fun printK300CpclStoreItemPreview(payloadJson: String): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        lastProtocolTested = K300_CPCL_STORE_ITEM_PREVIEW_PROTOCOL
        lastPrintResult = RESULT_FAILED

        val payload = try {
            UrovoK300PrinterManagerClient.StoreItemLabelPreviewPayload.fromJson(payloadJson)
        } catch (error: JSONException) {
            return previewPrintFailure("K300 CPCL STORE_ITEM preview payload is invalid: ${error.message ?: "invalid JSON"}.")
                .toString()
        } catch (error: IllegalArgumentException) {
            return previewPrintFailure("K300 CPCL STORE_ITEM preview payload is invalid: ${error.message ?: "invalid payload"}.")
                .toString()
        }

        val command = buildK300CpclStoreItemPreviewCommand(payload)
        return sendOneShotK300SppDiagnostic(
            protocol = K300_CPCL_STORE_ITEM_PREVIEW_PROTOCOL,
            command = command,
            bytes = command.toByteArray(Charset.forName("GBK")),
            operations = listOf(
                "open_spp_socket",
                "write_cpcl_store_item_preview",
                "flush",
                "close_spp_socket",
            ),
            useK300CpclInFlightGuard = true,
            enforcePreviewBusyGuard = false,
        )
    }

    @JavascriptInterface
    @Synchronized
    fun printK300TsplMinText(): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        val command = buildK300TsplMinTextCommand()
        return sendOneShotK300SppDiagnostic(
            protocol = K300_TSPL_MIN_TEXT_PROTOCOL,
            command = command,
            bytes = command.toByteArray(Charset.forName("GBK")),
            operations = listOf(
                "open_spp_socket",
                "write_tspl_min_text",
                "flush",
                "close_spp_socket",
            ),
        )
    }

    @JavascriptInterface
    @Synchronized
    fun printK300TsplBlackBox(): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        val command = buildK300TsplBlackBoxCommand()
        return sendOneShotK300SppDiagnostic(
            protocol = K300_TSPL_BLACK_BOX_PROTOCOL,
            command = command,
            bytes = command.toByteArray(Charset.forName("GBK")),
            operations = listOf(
                "open_spp_socket",
                "write_tspl_black_box",
                "flush",
                "close_spp_socket",
            ),
        )
    }

    @JavascriptInterface
    @Synchronized
    fun testK300SppConnection(): String {
        if (!isTrustedPage()) return untrustedStatus().toString()
        lastProtocolTested = K300_SPP_CONNECT_TEST_PROTOCOL
        return runK300SppConnectionProbe().toString()
    }

    private fun printStoreItemLabelPreviewWithProtocol(
        payloadJson: String,
        protocol: String,
        printer: (ChitengS1OfficialPrinterClient.StoreItemLabelPreviewPayload) -> String,
    ): String {
        if (!isTrustedPage()) return untrustedStatus().toString()

        lastProtocolTested = protocol
        lastPrintResult = RESULT_FAILED

        val payload = try {
            ChitengS1OfficialPrinterClient.StoreItemLabelPreviewPayload.fromJson(payloadJson)
        } catch (error: JSONException) {
            return previewPrintFailure("STORE_ITEM preview label payload is invalid: ${error.message ?: "invalid JSON"}.")
                .toString()
        } catch (error: IllegalArgumentException) {
            return previewPrintFailure("STORE_ITEM preview label payload is invalid: ${error.message ?: "invalid payload"}.")
                .toString()
        }

        return printer(payload)
    }

    @JavascriptInterface
    @Synchronized
    fun getLastPrintResult(): String {
        return guardedStatus().toString()
    }

    @Synchronized
    fun destroy() {
        stopPrinterDiscoveryInternal(bluetoothAdapter(), nextStatus = DISCOVERY_IDLE)
        chitengOfficialPrinterClient.disconnect()
        closeSocket()
    }

    private fun connectExternalK300Spp(selection: PrinterSelection): JSONObject {
        selectedProfile = DirectLoopPrinterProfile.UROVO_K300
        selectedPrinterAddress = selection.address
        selectedPrinterName = selection.name
        connectionStatus = STATUS_CONNECTING
        lastError = ""
        chitengOfficialPrinterClient.disconnect()
        closeSocket()
        return runK300SppConnectionProbe()
    }

    private fun printUrovoK300Diagnostic(
        protocol: String,
        labelSize: String,
        printer: () -> UrovoK300PrinterManagerClient.UrovoK300PrintResult,
    ): String {
        lastProtocolTested = protocol
        lastPrintResult = RESULT_FAILED
        selectedProfile = DirectLoopPrinterProfile.UROVO_K300

        if (System.currentTimeMillis() < previewPrintBusyUntilMs) {
            return previewPrintFailure("Printer is busy. Wait before printing again.").toString()
        }

        previewPrintBusyUntilMs = System.currentTimeMillis() + PREVIEW_PRINT_BUSY_WINDOW_MS
        chitengOfficialPrinterClient.disconnect()
        closeSocket()
        connectionStatus = STATUS_CONNECTING
        lastPreviewTransport = PREVIEW_TRANSPORT_UROVO_PRINTER_MANAGER
        lastPreviewLabelSize = labelSize
        lastPreviewTsplCommand = ""
        lastPreviewTsplLinesOverride = null
        lastPreviewTsplSentAt = ""
        lastPreviewTsplBytes = 0

        return try {
            val result = printer()
            finishUrovoK300Diagnostic(result).toString()
        } catch (error: RuntimeException) {
            previewPrintBusyUntilMs = 0L
            connectionStatus = STATUS_ERROR
            previewPrintFailure("Urovo K300 diagnostic failed: ${error.message ?: "unknown error"}.").toString()
        }
    }

    private fun finishUrovoK300Diagnostic(
        result: UrovoK300PrinterManagerClient.UrovoK300PrintResult,
    ): JSONObject {
        applyUrovoResult(result)
        lastPreviewSdkOperations = result.operations

        if (result.success) {
            previewPrintBusyUntilMs = System.currentTimeMillis() + PREVIEW_PRINT_BUSY_WINDOW_MS
            lastPrintResult = RESULT_SUCCESS
            lastError = ""
            return statusJson(bridgeAvailable = true, errorOverride = "", refreshHealth = false)
        }

        previewPrintBusyUntilMs = 0L
        connectionStatus = STATUS_ERROR
        return previewPrintFailure(result.message)
    }

    private fun printOfficialChitengTestLabel(): String {
        lastProtocolTested = BluetoothPrinterTestProtocol.CHITENG_S1_OFFICIAL.name
        lastPrintResult = RESULT_FAILED

        if (selectedPrinterAddress.isBlank()) {
            return fail(
                "No Bluetooth printer is selected. Select a paired Chiteng S1 printer before printing the official SDK test label.",
                printAttempt = true,
            ).toString()
        }

        val adapter = bluetoothAdapter()
            ?: return fail("Bluetooth adapter is not available on this device.", printAttempt = true).toString()
        if (!ensureBluetoothConnectPermission()) return guardedStatus().toString()
        if (!isBluetoothEnabled(adapter)) {
            return fail("Bluetooth is disabled. Enable Bluetooth before printing the official SDK test label.", printAttempt = true).toString()
        }
        if (!chitengOfficialPrinterClient.isSdkAvailable()) {
            return fail("Chiteng official CTPL SDK is not available in this build.", printAttempt = true).toString()
        }
        if (bondedDeviceForAddress(adapter, selectedPrinterAddress) == null) {
            return fail("请先在 Android 系统蓝牙中完成配对后再连接。", printAttempt = true).toString()
        }

        closeSocket()
        connectionStatus = STATUS_CONNECTING

        val result = chitengOfficialPrinterClient.printOfficialDiagnosticLabel(
            address = selectedPrinterAddress,
            name = selectedPrinterName,
        )

        return if (result.success) {
            connectionStatus = STATUS_CONNECTED
            lastPrintResult = RESULT_SUCCESS
            lastError = ""
            guardedStatus().toString()
        } else {
            connectionStatus = STATUS_ERROR
            fail(result.message, printAttempt = true).toString()
        }
    }

    private fun printOfficialStoreItemLabelPreviewCtplNoLabelMode(
        payload: ChitengS1OfficialPrinterClient.StoreItemLabelPreviewPayload,
    ): String {
        previewPrintTargetOrFailure() ?: return statusJson(bridgeAvailable = true, refreshHealth = false).toString()

        previewPrintBusyUntilMs = System.currentTimeMillis() + PREVIEW_PRINT_BUSY_WINDOW_MS
        chitengOfficialPrinterClient.disconnect()
        closeSocket()
        connectionStatus = STATUS_CONNECTING

        return try {
            lastPreviewTransport = PREVIEW_TRANSPORT_CTPL_SDK_NO_LABEL_MODE
            lastPreviewLabelSize = payload.templateSize
            lastPreviewTsplCommand = ""
            lastPreviewTsplLinesOverride = null
            lastPreviewTsplSentAt = ""
            lastPreviewTsplBytes = 0
            lastPreviewSdkOperations = payload.toCtplNoLabelModeOperationSummary()
            val result = chitengOfficialPrinterClient.printStoreItemLabelPreviewCtplNoLabelMode(
                address = selectedPrinterAddress,
                name = selectedPrinterName,
                payload = payload,
            )
            previewPrintBusyUntilMs = System.currentTimeMillis() + PREVIEW_PRINT_BUSY_WINDOW_MS
            syncOfficialSdkSummary()
            if (result.success) {
                connectionStatus = STATUS_CONNECTED
                lastPrintResult = RESULT_SUCCESS
                lastError = ""
                statusJson(bridgeAvailable = true, errorOverride = "", refreshHealth = false).toString()
            } else {
                previewPrintBusyUntilMs = 0L
                connectionStatus = STATUS_ERROR
                previewPrintFailure(result.message).toString()
            }
        } catch (error: SecurityException) {
            previewPrintBusyUntilMs = 0L
            connectionStatus = STATUS_ERROR
            previewPrintFailure("Bluetooth permission denied while printing STORE_ITEM preview through Chiteng official SDK.").toString()
        } catch (error: RuntimeException) {
            previewPrintBusyUntilMs = 0L
            connectionStatus = STATUS_ERROR
            previewPrintFailure("STORE_ITEM preview CTPL print failed: ${error.message ?: "unknown error"}.").toString()
        }
    }

    private fun printOfficialStoreItemLabelPreviewCtplBitmapDemo(
        payload: ChitengS1OfficialPrinterClient.StoreItemLabelPreviewPayload,
    ): String {
        previewPrintTargetOrFailure() ?: return statusJson(bridgeAvailable = true, refreshHealth = false).toString()

        previewPrintBusyUntilMs = System.currentTimeMillis() + PREVIEW_PRINT_BUSY_WINDOW_MS
        chitengOfficialPrinterClient.disconnect()
        closeSocket()
        connectionStatus = STATUS_CONNECTING

        return try {
            lastPreviewTransport = PREVIEW_TRANSPORT_CTPL_SDK_BITMAP_DEMO
            lastPreviewLabelSize = payload.templateSize
            lastPreviewTsplCommand = ""
            lastPreviewTsplLinesOverride = null
            lastPreviewTsplSentAt = ""
            lastPreviewTsplBytes = 0
            lastPreviewSdkOperations = payload.toCtplBitmapDemoOperationSummary()
            val result = chitengOfficialPrinterClient.printStoreItemLabelPreviewCtplBitmapDemo(
                address = selectedPrinterAddress,
                name = selectedPrinterName,
                payload = payload,
            )
            previewPrintBusyUntilMs = System.currentTimeMillis() + PREVIEW_PRINT_BUSY_WINDOW_MS
            syncOfficialSdkSummary()
            if (result.success) {
                connectionStatus = STATUS_CONNECTED
                lastPrintResult = RESULT_SUCCESS
                lastError = ""
                statusJson(bridgeAvailable = true, errorOverride = "", refreshHealth = false).toString()
            } else {
                previewPrintBusyUntilMs = 0L
                connectionStatus = STATUS_ERROR
                previewPrintFailure(result.message).toString()
            }
        } catch (error: SecurityException) {
            previewPrintBusyUntilMs = 0L
            connectionStatus = STATUS_ERROR
            previewPrintFailure("Bluetooth permission denied while printing STORE_ITEM preview bitmap through Chiteng official SDK.").toString()
        } catch (error: RuntimeException) {
            previewPrintBusyUntilMs = 0L
            connectionStatus = STATUS_ERROR
            previewPrintFailure("STORE_ITEM preview CTPL bitmap print failed: ${error.message ?: "unknown error"}.").toString()
        }
    }

    private fun sendRawStoreItemPreviewTspl(
        payload: ChitengS1OfficialPrinterClient.StoreItemLabelPreviewPayload,
    ): String {
        val target = previewPrintTargetOrFailure() ?: return statusJson(bridgeAvailable = true, refreshHealth = false).toString()

        previewPrintBusyUntilMs = System.currentTimeMillis() + PREVIEW_PRINT_BUSY_WINDOW_MS
        chitengOfficialPrinterClient.disconnect()
        closeSocket()
        connectionStatus = STATUS_CONNECTING

        val tspl = payload.legacyRawTsplForDiagnostics()
        val bytes = tspl.toByteArray(Charset.forName("GBK"))
        lastPreviewTransport = PREVIEW_TRANSPORT_RAW_TSPL_SPP
        lastPreviewLabelSize = payload.templateSize
        lastPreviewTsplCommand = tspl
        lastPreviewTsplLinesOverride = null
        lastPreviewTsplSentAt = timestamp()
        lastPreviewTsplBytes = bytes.size
        lastPreviewSdkOperations = emptyList()

        return try {
            connectSocket(
                adapter = target.adapter,
                selection = PrinterSelection(
                    profile = DirectLoopPrinterProfile.CHITENG_S1_OFFICIAL,
                    address = selectedPrinterAddress,
                    name = selectedPrinterName,
                ),
                bondedDevice = target.bondedDevice,
            )
            val stream = outputStream ?: throw IOException("Bluetooth SPP output stream is not available.")
            stream.write(bytes)
            stream.flush()
            previewPrintBusyUntilMs = System.currentTimeMillis() + PREVIEW_PRINT_BUSY_WINDOW_MS
            connectionStatus = STATUS_CONNECTED
            lastPrintResult = RESULT_SUCCESS
            lastError = ""
            statusJson(bridgeAvailable = true, errorOverride = "", refreshHealth = false).toString()
        } catch (error: SecurityException) {
            previewPrintBusyUntilMs = 0L
            connectionStatus = STATUS_ERROR
            previewPrintFailure("Bluetooth permission denied while sending STORE_ITEM raw TSPL preview.").toString()
        } catch (error: IOException) {
            previewPrintBusyUntilMs = 0L
            connectionStatus = STATUS_ERROR
            previewPrintFailure("STORE_ITEM preview raw TSPL send failed: ${error.message ?: "unknown error"}.").toString()
        } catch (error: RuntimeException) {
            previewPrintBusyUntilMs = 0L
            connectionStatus = STATUS_ERROR
            previewPrintFailure("STORE_ITEM preview raw TSPL failed: ${error.message ?: "unknown error"}.").toString()
        }
    }

    private fun buildS1RawTsplMinTextCommand(): String {
        val lines = listOf(
            "SIZE 40 mm,30 mm",
            "TEXT 100,150,\"TSS24.BF2\",0,1,1,\"CHI TENG TSPL MANUAL\"",
            "PRINT 1,1",
        )
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    private fun buildS1RawTsplBlackBoxCommand(): String {
        val lines = listOf(
            "SIZE 40 mm,30 mm",
            "CLS",
            "SPEED 2",
            "DENSITY 12",
            "DIRECTION 0",
            "BAR 20,20,200,100",
            "PRINT 1,1",
        )
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    private fun buildK300CpclMinTextCommand(): String {
        val lines = listOf(
            "! 0 200 200 240 1",
            "TEXT 4 0 20 30 K300 CPCL TEST",
            "TEXT 4 0 20 70 5261300000038",
            "PRINT",
        )
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    private fun buildK300CpclCode128TestCommand(): String {
        val lines = listOf(
            "! 0 200 200 240 1",
            "TEXT 4 0 20 20 CPCL CODE128 TEST",
            "BARCODE 128 2 1 70 20 70 5261300000038",
            "TEXT 4 0 20 155 5261300000038",
            "PRINT",
        )
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    private fun buildK300CpclCode128WideTestCommand(): String {
        val lines = listOf(
            "! 0 200 200 240 1",
            "TEXT 4 0 20 15 CODE128 WIDE",
            "BARCODE 128 3 1 90 15 55 5261300000038",
            "TEXT 4 0 35 155 5261300000038",
            "PRINT",
        )
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    private fun buildK300CpclCode128TallTestCommand(): String {
        val lines = listOf(
            "! 0 200 200 240 1",
            "TEXT 4 0 20 15 CODE128 TALL",
            "BARCODE 128 2 1 100 15 55 5261300000038",
            "TEXT 4 0 35 170 5261300000038",
            "PRINT",
        )
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    private fun buildK300CpclCode128QuietZoneTestCommand(): String {
        val lines = listOf(
            "! 0 200 200 240 1",
            "TEXT 4 0 30 15 CODE128 QUIET",
            "BARCODE 128 2 1 85 35 60 5261300000038",
            "TEXT 4 0 55 160 5261300000038",
            "PRINT",
        )
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    private fun buildK300CpclCode128CompactTopTestCommand(): String {
        val lines = listOf(
            "! 0 200 200 240 1",
            "BARCODE 128 2 1 85 20 25 5261300000038",
            "TEXT 4 0 45 125 5261300000038",
            "TEXT 4 0 20 170 CPCL SCAN TEST",
            "PRINT",
        )
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    private fun validateK300CpclRawPreviewPayload(payloadJson: String): String {
        val payload = JSONObject(payloadJson)
        val labelTemplateSize = payload.optString("label_template_size").trim()
        require(labelTemplateSize == "40x30") { "label_template_size must be 40x30." }

        val protocol = payload.optString("protocol").trim().uppercase(Locale.US)
        require(protocol == "CPCL") { "protocol must be CPCL." }

        val cpclCommand = payload.optString("cpcl_command")
        validateK300CpclCommand(cpclCommand)
        return cpclCommand
    }

    private fun validateK300CpclRawBatchPayload(payloadJson: String): K300CpclRawBatchPayload {
        val payload = JSONObject(payloadJson)
        val labelTemplateSize = payload.optString("label_template_size").trim()
        require(labelTemplateSize == "40x30") { "label_template_size must be 40x30." }

        val protocol = payload.optString("protocol").trim().uppercase(Locale.US)
        require(protocol == "CPCL") { "protocol must be CPCL." }

        val labels = payload.optJSONArray("labels") ?: throw IllegalArgumentException("labels is required.")
        require(labels.length() > 0) { "labels must not be empty." }
        require(labels.length() <= 100) { "labels max count is 100." }

        val parsedLabels = mutableListOf<K300CpclRawBatchLabel>()
        for (index in 0 until labels.length()) {
            val label = labels.optJSONObject(index)
                ?: throw IllegalArgumentException("labels[$index] must be an object.")
            val testName = label.optString("test_name").trim().ifBlank { "label_${index + 1}" }
            val cpclCommand = label.optString("cpcl_command")
            val bytes = validateK300CpclCommand(cpclCommand)
            parsedLabels.add(
                K300CpclRawBatchLabel(
                    testName = testName,
                    cpclCommand = cpclCommand,
                    bytes = bytes,
                ),
            )
        }

        return K300CpclRawBatchPayload(
            batchName = payload.optString("batch_name").trim().ifBlank { "k300_cpcl_raw_batch" },
            labels = parsedLabels,
        )
    }

    private fun validateK300CpclCommand(cpclCommand: String): ByteArray {
        require(cpclCommand.isNotBlank()) { "cpcl_command is required." }
        require(Regex("\\bPRINT\\b", RegexOption.IGNORE_CASE).containsMatchIn(cpclCommand)) {
            "cpcl_command must include PRINT."
        }
        val dangerousCommands = Regex("\\b(FILE|DELETE|FORMAT|DOWNLOAD|RUN|EXEC)\\b", RegexOption.IGNORE_CASE)
        require(!dangerousCommands.containsMatchIn(cpclCommand)) {
            "cpcl_command contains unsupported CPCL command."
        }

        val cpclBytes = cpclCommand.toByteArray(Charset.forName("GBK"))
        require(cpclBytes.size < 2000) { "cpcl_command must be under 2000 bytes." }
        return cpclBytes
    }

    private fun buildK300CpclStoreItemPreviewCommand(
        payload: UrovoK300PrinterManagerClient.StoreItemLabelPreviewPayload,
    ): String {
        val label = payload.label
        val lines = listOf(
            "! 0 200 200 240 1",
            "TEXT 4 0 20 18 ${label.shortHeaderText()}",
            "TEXT 7 0 20 50 KES ${label.priceKes}",
            "BARCODE 128 2 1 70 20 105 ${label.machineCode}",
            "TEXT 4 0 35 190 ${label.machineCode}",
            "PRINT",
        )
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    private fun buildK300TsplMinTextCommand(): String {
        val lines = listOf(
            "SIZE 40 mm,30 mm",
            "CLS",
            "TEXT 20,40,\"TSS24.BF2\",0,1,1,\"K300 TSPL TEST\"",
            "TEXT 20,90,\"TSS24.BF2\",0,1,1,\"5261300000038\"",
            "PRINT 1,1",
        )
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    private fun buildK300TsplBlackBoxCommand(): String {
        val lines = listOf(
            "SIZE 40 mm,30 mm",
            "CLS",
            "DENSITY 12",
            "BAR 20,20,200,100",
            "PRINT 1,1",
        )
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    private fun runK300SppConnectionProbe(): JSONObject {
        selectedProfile = DirectLoopPrinterProfile.UROVO_K300
        lastProtocolTested = K300_SPP_CONNECT_TEST_PROTOCOL
        lastPrintResult = RESULT_FAILED
        lastPreviewTransport = PREVIEW_TRANSPORT_K300_BLUETOOTH_SPP
        lastPreviewLabelSize = ""
        lastPreviewTsplCommand = ""
        lastPreviewTsplLinesOverride = null
        lastPreviewTsplSentAt = timestamp()
        lastPreviewTsplBytes = 2
        lastPreviewSdkOperations = K300_SPP_CONNECT_TEST_OPERATIONS

        val checkedAt = timestamp()
        val target = k300SppConnectionTargetOrFailure(checkedAt)
            ?: return statusJson(bridgeAvailable = true, refreshHealth = false)

        previewPrintBusyUntilMs = System.currentTimeMillis() + PREVIEW_PRINT_BUSY_WINDOW_MS
        chitengOfficialPrinterClient.disconnect()
        closeSocket()
        connectionStatus = STATUS_CONNECTING

        return try {
            writeOneShotSppBytes(target.adapter, target.bondedDevice, ESC_INIT_BYTES, sleepMs = 300L)
            previewPrintBusyUntilMs = System.currentTimeMillis() + PREVIEW_PRINT_BUSY_WINDOW_MS
            connectionStatus = STATUS_CONNECTED
            printerOnlineStatus = ONLINE_UNKNOWN
            k300SppAvailable = true
            k300SppLastCheckedAt = checkedAt
            k300SppLastError = ""
            lastPrintResult = RESULT_SUCCESS
            lastError = ""
            statusJson(bridgeAvailable = true, errorOverride = "", refreshHealth = false)
        } catch (error: SecurityException) {
            previewPrintBusyUntilMs = 0L
            k300SppConnectionFailure("Bluetooth permission denied while testing K300 Bluetooth SPP connection.", checkedAt)
        } catch (error: IOException) {
            previewPrintBusyUntilMs = 0L
            k300SppConnectionFailure(
                "K300 Bluetooth SPP connection failed: ${error.message ?: "unknown error"}.",
                checkedAt,
            )
        } catch (error: RuntimeException) {
            previewPrintBusyUntilMs = 0L
            k300SppConnectionFailure(
                "K300 Bluetooth SPP connection test failed: ${error.message ?: "unknown error"}.",
                checkedAt,
            )
        }
    }

    private fun sendOneShotK300SppDiagnostic(
        protocol: String,
        command: String,
        bytes: ByteArray,
        operations: List<String>,
        useK300CpclInFlightGuard: Boolean = false,
        enforcePreviewBusyGuard: Boolean = true,
    ): String {
        selectedProfile = DirectLoopPrinterProfile.UROVO_K300
        lastProtocolTested = protocol
        lastPrintResult = RESULT_FAILED

        if (useK300CpclInFlightGuard && k300CpclPrintInProgress) {
            return previewPrintFailure("K300 CPCL print is already running.").toString()
        }

        val target = k300SppTargetOrFailure(enforcePreviewBusyGuard = enforcePreviewBusyGuard)
            ?: return statusJson(bridgeAvailable = true, refreshHealth = false).toString()

        if (useK300CpclInFlightGuard) {
            k300CpclPrintInProgress = true
        } else {
            previewPrintBusyUntilMs = System.currentTimeMillis() + PREVIEW_PRINT_BUSY_WINDOW_MS
        }
        chitengOfficialPrinterClient.disconnect()
        closeSocket()
        connectionStatus = STATUS_CONNECTING
        lastPreviewTransport = PREVIEW_TRANSPORT_K300_BLUETOOTH_SPP
        lastPreviewLabelSize = "40x30"
        lastPreviewTsplCommand = command
        lastPreviewTsplLinesOverride = null
        lastPreviewTsplSentAt = timestamp()
        lastPreviewTsplBytes = bytes.size
        lastPreviewSdkOperations = operations
        resetK300BatchSummary()

        return try {
            writeOneShotSppBytes(target.adapter, target.bondedDevice, bytes)
            if (!useK300CpclInFlightGuard) {
                previewPrintBusyUntilMs = System.currentTimeMillis() + PREVIEW_PRINT_BUSY_WINDOW_MS
            }
            connectionStatus = STATUS_DISCONNECTED
            printerOnlineStatus = ONLINE_UNKNOWN
            lastPrintResult = RESULT_SUCCESS
            lastError = ""
            statusJson(bridgeAvailable = true, errorOverride = "", refreshHealth = false).toString()
        } catch (error: SecurityException) {
            if (!useK300CpclInFlightGuard) previewPrintBusyUntilMs = 0L
            connectionStatus = STATUS_ERROR
            previewPrintFailure("Bluetooth permission denied while sending K300 Bluetooth SPP diagnostic.").toString()
        } catch (error: IOException) {
            if (!useK300CpclInFlightGuard) previewPrintBusyUntilMs = 0L
            connectionStatus = STATUS_ERROR
            previewPrintFailure("K300 Bluetooth SPP diagnostic send failed: ${error.message ?: "unknown error"}.").toString()
        } catch (error: RuntimeException) {
            if (!useK300CpclInFlightGuard) previewPrintBusyUntilMs = 0L
            connectionStatus = STATUS_ERROR
            previewPrintFailure("K300 Bluetooth SPP diagnostic failed: ${error.message ?: "unknown error"}.").toString()
        } finally {
            if (useK300CpclInFlightGuard) {
                k300CpclPrintInProgress = false
            }
        }
    }

    private fun sendK300CpclCode128Variant(
        protocol: String,
        command: String,
        writeOperation: String,
    ): String {
        return sendOneShotK300SppDiagnostic(
            protocol = protocol,
            command = command,
            bytes = command.toByteArray(Charset.forName("GBK")),
            operations = listOf(
                "open_spp_socket",
                writeOperation,
                "flush",
                "close_spp_socket",
            ),
            useK300CpclInFlightGuard = true,
            enforcePreviewBusyGuard = false,
        )
    }

    private fun sendK300CpclRawBatch(payload: K300CpclRawBatchPayload): String {
        selectedProfile = DirectLoopPrinterProfile.UROVO_K300
        lastProtocolTested = K300_CPCL_RAW_BATCH_PROTOCOL
        lastPrintResult = RESULT_FAILED

        if (k300CpclPrintInProgress) {
            return previewPrintFailure("K300 CPCL print is already running.").toString()
        }

        val target = k300SppTargetOrFailure(enforcePreviewBusyGuard = false)
            ?: return statusJson(bridgeAvailable = true, refreshHealth = false).toString()

        val startedAt = timestamp()
        k300CpclPrintInProgress = true
        chitengOfficialPrinterClient.disconnect()
        closeSocket()
        connectionStatus = STATUS_CONNECTING
        lastPreviewTransport = PREVIEW_TRANSPORT_K300_BLUETOOTH_SPP
        lastPreviewLabelSize = "40x30"
        lastPreviewTsplCommand = "K300 CPCL raw batch: labels=${payload.labels.size}"
        lastPreviewTsplLinesOverride = listOf(
            "batch_name=${payload.batchName}",
            "label_count=${payload.labels.size}",
            "first_test_name=${payload.labels.first().testName}",
            "last_test_name=${payload.labels.last().testName}",
        )
        lastPreviewTsplSentAt = startedAt
        lastPreviewTsplBytes = payload.labels.sumOf { it.bytes.size }
        lastPreviewSdkOperations = listOf("open_spp_socket", "write_cpcl_raw_batch_start") +
            payload.labels.mapIndexed { index, _ -> "write_cpcl_raw_batch_label_${index + 1}" } +
            listOf("flush", "close_spp_socket")
        k300BatchLabelCount = payload.labels.size
        k300BatchSentCount = 0
        k300BatchFailedCount = 0
        k300BatchStartedAt = startedAt
        k300BatchFinishedAt = ""
        k300BatchLastError = ""

        return try {
            connectSocket(
                adapter = target.adapter,
                selection = PrinterSelection(
                    profile = DirectLoopPrinterProfile.UROVO_K300,
                    address = selectedPrinterAddress,
                    name = selectedPrinterName,
                ),
                bondedDevice = target.bondedDevice,
            )
            val stream = outputStream ?: throw IOException("Bluetooth SPP output stream is not available.")
            for ((index, label) in payload.labels.withIndex()) {
                stream.write(label.bytes)
                stream.flush()
                k300BatchSentCount = index + 1
                if (index < payload.labels.size - 1) {
                    Thread.sleep(500L)
                }
            }
            k300BatchFinishedAt = timestamp()
            connectionStatus = STATUS_DISCONNECTED
            printerOnlineStatus = ONLINE_UNKNOWN
            lastPrintResult = RESULT_SUCCESS
            lastError = ""
            statusJson(bridgeAvailable = true, errorOverride = "", refreshHealth = false).toString()
        } catch (error: SecurityException) {
            k300BatchFailedCount = payload.labels.size - k300BatchSentCount
            k300BatchFinishedAt = timestamp()
            k300BatchLastError = "Bluetooth permission denied while sending K300 CPCL raw batch."
            connectionStatus = STATUS_ERROR
            previewPrintFailure(k300BatchLastError).toString()
        } catch (error: IOException) {
            k300BatchFailedCount = payload.labels.size - k300BatchSentCount
            k300BatchFinishedAt = timestamp()
            k300BatchLastError = "K300 CPCL raw batch send failed: ${error.message ?: "unknown error"}."
            connectionStatus = STATUS_ERROR
            previewPrintFailure(k300BatchLastError).toString()
        } catch (error: RuntimeException) {
            k300BatchFailedCount = payload.labels.size - k300BatchSentCount
            k300BatchFinishedAt = timestamp()
            k300BatchLastError = "K300 CPCL raw batch failed: ${error.message ?: "unknown error"}."
            connectionStatus = STATUS_ERROR
            previewPrintFailure(k300BatchLastError).toString()
        } finally {
            k300CpclPrintInProgress = false
            closeSocket()
        }
    }

    private fun sendOneShotRawS1DiagnosticTspl(
        protocol: String,
        command: String,
    ): String {
        lastProtocolTested = protocol
        lastPrintResult = RESULT_FAILED

        val target = previewPrintTargetOrFailure() ?: return statusJson(bridgeAvailable = true, refreshHealth = false).toString()

        previewPrintBusyUntilMs = System.currentTimeMillis() + PREVIEW_PRINT_BUSY_WINDOW_MS
        chitengOfficialPrinterClient.disconnect()
        closeSocket()
        connectionStatus = STATUS_CONNECTING

        val bytes = command.toByteArray(Charset.forName("GBK"))
        lastPreviewTransport = PREVIEW_TRANSPORT_RAW_TSPL_SPP
        lastPreviewLabelSize = "40x30"
        lastPreviewTsplCommand = command
        lastPreviewTsplLinesOverride = null
        lastPreviewTsplSentAt = timestamp()
        lastPreviewTsplBytes = bytes.size
        lastPreviewSdkOperations = emptyList()

        return try {
            connectSocket(
                adapter = target.adapter,
                selection = PrinterSelection(
                    profile = DirectLoopPrinterProfile.CHITENG_S1_OFFICIAL,
                    address = selectedPrinterAddress,
                    name = selectedPrinterName,
                ),
                bondedDevice = target.bondedDevice,
            )
            val stream = outputStream ?: throw IOException("Bluetooth SPP output stream is not available.")
            stream.write(bytes)
            stream.flush()
            Thread.sleep(400L)
            previewPrintBusyUntilMs = System.currentTimeMillis() + PREVIEW_PRINT_BUSY_WINDOW_MS
            connectionStatus = STATUS_DISCONNECTED
            lastPrintResult = RESULT_SUCCESS
            lastError = ""
            statusJson(bridgeAvailable = true, errorOverride = "", refreshHealth = false).toString()
        } catch (error: SecurityException) {
            previewPrintBusyUntilMs = 0L
            connectionStatus = STATUS_ERROR
            previewPrintFailure("Bluetooth permission denied while sending S1 raw TSPL diagnostic.").toString()
        } catch (error: IOException) {
            previewPrintBusyUntilMs = 0L
            connectionStatus = STATUS_ERROR
            previewPrintFailure("S1 raw TSPL diagnostic send failed: ${error.message ?: "unknown error"}.").toString()
        } catch (error: RuntimeException) {
            previewPrintBusyUntilMs = 0L
            connectionStatus = STATUS_ERROR
            previewPrintFailure("S1 raw TSPL diagnostic failed: ${error.message ?: "unknown error"}.").toString()
        } finally {
            closeSocket()
        }
    }

    private fun k300SppTargetOrFailure(enforcePreviewBusyGuard: Boolean = true): PreviewPrintTarget? {
        if (selectedPrinterAddress.isBlank()) {
            previewPrintFailure(
                "No Bluetooth printer is selected. Select a paired K300 printer before running K300 Bluetooth SPP diagnostics.",
            )
            return null
        }

        val adapter = bluetoothAdapter()
        if (adapter == null) {
            previewPrintFailure("Bluetooth adapter is not available on this device.")
            return null
        }
        if (!ensureBluetoothConnectPermission()) {
            previewPrintFailure(lastError.ifBlank { "Bluetooth permission is required. Grant Nearby devices permission and retry." })
            return null
        }
        if (!isBluetoothEnabled(adapter)) {
            previewPrintFailure("Bluetooth is disabled. Enable Bluetooth before running K300 Bluetooth SPP diagnostics.")
            return null
        }
        val bondedDevice = bondedDeviceForAddress(adapter, selectedPrinterAddress)
        if (bondedDevice == null) {
            previewPrintFailure("请先在 Android 系统蓝牙中完成配对后再连接。")
            return null
        }
        if (enforcePreviewBusyGuard && System.currentTimeMillis() < previewPrintBusyUntilMs) {
            previewPrintFailure("Printer is busy. Wait before printing again.")
            return null
        }
        if (selectedPrinterName.isBlank()) {
            selectedPrinterName = safeDeviceName(bondedDevice)
        }
        return PreviewPrintTarget(adapter = adapter, bondedDevice = bondedDevice)
    }

    private fun k300SppConnectionTargetOrFailure(checkedAt: String): PreviewPrintTarget? {
        if (selectedPrinterAddress.isBlank()) {
            k300SppConnectionFailure(
                "No Bluetooth printer is selected. Select a paired K300 printer before testing K300 Bluetooth SPP connection.",
                checkedAt,
            )
            return null
        }

        val adapter = bluetoothAdapter()
        if (adapter == null) {
            k300SppConnectionFailure("Bluetooth adapter is not available on this device.", checkedAt)
            return null
        }
        if (!ensureBluetoothConnectPermission()) {
            k300SppConnectionFailure(
                lastError.ifBlank { "Bluetooth permission is required. Grant Nearby devices permission and retry." },
                checkedAt,
            )
            return null
        }
        if (!isBluetoothEnabled(adapter)) {
            k300SppConnectionFailure("Bluetooth is disabled. Enable Bluetooth before testing K300 Bluetooth SPP connection.", checkedAt)
            return null
        }
        val bondedDevice = bondedDeviceForAddress(adapter, selectedPrinterAddress)
        if (bondedDevice == null) {
            k300SppConnectionFailure("请先在 Android 系统蓝牙中完成配对后再连接。", checkedAt)
            return null
        }
        if (System.currentTimeMillis() < previewPrintBusyUntilMs) {
            k300SppConnectionFailure("Printer is busy. Wait before printing again.", checkedAt)
            return null
        }
        if (selectedPrinterName.isBlank()) {
            selectedPrinterName = safeDeviceName(bondedDevice)
        }
        return PreviewPrintTarget(adapter = adapter, bondedDevice = bondedDevice)
    }

    private fun k300SppConnectionFailure(message: String, checkedAt: String): JSONObject {
        selectedProfile = DirectLoopPrinterProfile.UROVO_K300
        connectionStatus = STATUS_ERROR
        printerOnlineStatus = ONLINE_UNKNOWN
        lastProtocolTested = K300_SPP_CONNECT_TEST_PROTOCOL
        lastPreviewTransport = PREVIEW_TRANSPORT_K300_BLUETOOTH_SPP
        lastPrintResult = RESULT_FAILED
        lastError = message
        k300SppAvailable = false
        k300SppLastCheckedAt = checkedAt
        k300SppLastError = message
        lastPreviewTsplBytes = 2
        lastPreviewSdkOperations = K300_SPP_CONNECT_TEST_OPERATIONS
        return statusJson(bridgeAvailable = true, errorOverride = message, refreshHealth = false)
    }

    private fun previewPrintTargetOrFailure(): PreviewPrintTarget? {
        if (selectedPrinterAddress.isBlank()) {
            previewPrintFailure(
                "No Bluetooth printer is selected. Select a paired Chiteng S1 printer before printing STORE_ITEM preview labels.",
            )
            return null
        }

        val adapter = bluetoothAdapter()
        if (adapter == null) {
            previewPrintFailure("Bluetooth adapter is not available on this device.")
            return null
        }
        if (!ensureBluetoothConnectPermission()) {
            previewPrintFailure(lastError.ifBlank { "Bluetooth permission is required. Grant Nearby devices permission and retry." })
            return null
        }
        if (!isBluetoothEnabled(adapter)) {
            previewPrintFailure("Bluetooth is disabled. Enable Bluetooth before printing STORE_ITEM preview labels.")
            return null
        }
        val bondedDevice = bondedDeviceForAddress(adapter, selectedPrinterAddress)
        if (bondedDevice == null) {
            previewPrintFailure("请先在 Android 系统蓝牙中完成配对后再连接。")
            return null
        }
        if (System.currentTimeMillis() < previewPrintBusyUntilMs) {
            previewPrintFailure("Printer is busy. Wait before printing again.")
            return null
        }
        return PreviewPrintTarget(adapter = adapter, bondedDevice = bondedDevice)
    }

    private fun guardedStatus(): JSONObject {
        if (!isTrustedPage()) return untrustedStatus()
        return statusJson(bridgeAvailable = true)
    }

    private fun untrustedStatus(): JSONObject {
        val message = "DirectLoopPdaPrinter is only available on trusted FW-ERP host ${BuildConfig.FW_ERP_HOST}."
        lastError = message
        return statusJson(bridgeAvailable = false, errorOverride = message)
    }

    private fun fail(message: String, printAttempt: Boolean = false): JSONObject {
        lastError = message
        if (printAttempt) {
            lastPrintResult = RESULT_FAILED
        }
        connectionStatus = STATUS_ERROR
        printerOnlineStatus = ONLINE_ERROR
        printerHealthCheckedAt = timestamp()
        syncOfficialSdkSummary()
        return statusJson(bridgeAvailable = true, errorOverride = message)
    }

    private fun failDiscovery(message: String): JSONObject {
        lastError = message
        discoveryStatus = DISCOVERY_ERROR
        return statusJson(bridgeAvailable = true, errorOverride = message)
    }

    private fun previewPrintFailure(message: String): JSONObject {
        lastError = message
        lastPrintResult = RESULT_FAILED
        syncOfficialSdkSummary()
        return statusJson(bridgeAvailable = true, errorOverride = message, refreshHealth = false)
    }

    private fun statusJson(
        bridgeAvailable: Boolean,
        errorOverride: String? = null,
        refreshHealth: Boolean = true,
    ): JSONObject {
        val adapter = bluetoothAdapter()
        val bluetoothEnabled = adapter?.let { isBluetoothEnabled(it) } ?: false
        if (refreshHealth) {
            refreshPrinterHealth(bridgeAvailable, bluetoothEnabled)
        } else {
            syncOfficialSdkSummary()
        }
        val pairedPrinters = if (bridgeAvailable && bluetoothEnabled && hasBluetoothConnectPermission()) {
            pairedPrinterArray(adapter)
        } else {
            JSONArray()
        }
        val discoveredPrinters = if (bridgeAvailable && bluetoothEnabled && hasBluetoothConnectPermission()) {
            discoveredPrinterArray(adapter)
        } else {
            JSONArray()
        }

        val statusError = errorOverride
            ?: if (selectedProfile == DirectLoopPrinterProfile.UROVO_K300) null else permissionError()
            ?: when {
                selectedProfile == DirectLoopPrinterProfile.UROVO_K300 -> lastError
                adapter == null -> "Bluetooth adapter is not available on this device."
                !bluetoothEnabled -> "Bluetooth is disabled. Enable Bluetooth before testing a printer."
                pairedPrinters.length() == 0 -> "No paired Bluetooth printers found. Pair Chiteng S1 or Urovo in Android Bluetooth settings first."
                else -> lastError
            }

        return JSONObject()
            .put("bridge_available", bridgeAvailable)
            .put("bluetooth_enabled", bluetoothEnabled)
            .put("discovery_status", discoveryStatus)
            .put("discovered_printer_count", discoveredPrinters.length())
            .put("discovered_printers", discoveredPrinters)
            .put("paired_printer_count", pairedPrinters.length())
            .put("paired_printers", pairedPrinters)
            .put("selected_printer_name", selectedPrinterName)
            .put("selected_printer_address", selectedPrinterAddress)
            .put("selected_profile", selectedProfile.name)
            .put("connection_status", connectionStatus)
            .put("printer_online_status", printerOnlineStatus)
            .put("printer_health_checked_at", printerHealthCheckedAt)
            .put("official_sdk_available", officialSdkAvailable)
            .put("official_sdk_connected", officialSdkConnected)
            .put("official_sdk_last_message", officialSdkLastMessage)
            .put("official_sdk_last_error", officialSdkLastError)
            .put("urovo_printer_available", urovoPrinterAvailable)
            .put("urovo_last_status_code", urovoLastStatusCode ?: JSONObject.NULL)
            .put("urovo_last_status_text", urovoLastStatusText)
            .put("k300_spp_available", k300SppAvailable)
            .put("k300_spp_last_checked_at", k300SppLastCheckedAt)
            .put("k300_spp_last_error", k300SppLastError)
            .put("last_error", statusError)
            .put("last_protocol_tested", lastProtocolTested)
            .put("last_print_result", lastPrintResult)
            .put("last_preview_transport", lastPreviewTransport)
            .put("last_preview_label_size", lastPreviewLabelSize)
            .put("last_preview_tspl_sent_at", lastPreviewTsplSentAt)
            .put("last_preview_tspl_bytes", lastPreviewTsplBytes)
            .put("last_preview_tspl_command", lastPreviewTsplCommand)
            .put("last_preview_tspl_lines", previewTsplLines())
            .put("last_preview_sdk_operations", jsonArray(lastPreviewSdkOperations))
            .put("k300_batch_label_count", k300BatchLabelCount)
            .put("k300_batch_sent_count", k300BatchSentCount)
            .put("k300_batch_failed_count", k300BatchFailedCount)
            .put("k300_batch_started_at", k300BatchStartedAt)
            .put("k300_batch_finished_at", k300BatchFinishedAt)
            .put("k300_batch_last_error", k300BatchLastError)
    }

    private fun supportedAppInfoMethods(): JSONArray {
        return JSONArray()
            .put("getPrinterStatus")
            .put("connectPrinter")
            .put("disconnectPrinter")
            .put("printTestLabel")
            .put("printStoreItemLabelPreview")
            .put("printStoreItemLabelPreviewCtplNoLabelMode")
            .put("printStoreItemLabelPreviewCtplBitmapDemo")
            .put("printStoreItemLabelPreviewRawTspl")
            .put("printS1RawTsplMinText")
            .put("printS1RawTsplBlackBox")
            .put("getUrovoPrinterStatus")
            .put("printUrovoK300MinText")
            .put("printUrovoK300BlackBox")
            .put("printUrovoK300StoreItemPreview")
            .put("printK300EscposMinText")
            .put("printK300CpclMinText")
            .put("printK300CpclCode128Test")
            .put("printK300CpclCode128WideTest")
            .put("printK300CpclCode128TallTest")
            .put("printK300CpclCode128QuietZoneTest")
            .put("printK300CpclCode128CompactTopTest")
            .put("printK300CpclRawPreview")
            .put("printK300CpclRawBatch")
            .put("printK300CpclStoreItemPreview")
            .put("printK300TsplMinText")
            .put("printK300TsplBlackBox")
            .put("testK300SppConnection")
    }

    private fun tsplLines(command: String): JSONArray {
        return JSONArray().apply {
            command
                .split("\r\n")
                .filter { it.isNotBlank() }
                .forEach { line -> put(line) }
        }
    }

    private fun previewTsplLines(): JSONArray {
        val overrideLines = lastPreviewTsplLinesOverride
        if (overrideLines != null) {
            return jsonArray(overrideLines)
        }
        return tsplLines(lastPreviewTsplCommand)
    }

    private fun resetK300BatchSummary() {
        k300BatchLabelCount = 0
        k300BatchSentCount = 0
        k300BatchFailedCount = 0
        k300BatchStartedAt = ""
        k300BatchFinishedAt = ""
        k300BatchLastError = ""
    }

    private fun jsonArray(values: List<String>): JSONArray {
        return JSONArray().apply {
            values.forEach { value -> put(value) }
        }
    }

    private fun refreshPrinterHealth(
        bridgeAvailable: Boolean,
        bluetoothEnabled: Boolean,
    ) {
        syncOfficialSdkSummary()
        if (!bridgeAvailable) {
            printerOnlineStatus = ONLINE_UNKNOWN
            return
        }

        if (selectedProfile == DirectLoopPrinterProfile.UROVO_K300) {
            refreshExternalK300SppHealth()
            return
        }

        if (!bluetoothEnabled) {
            if (connectionStatus == STATUS_CONNECTED || connectionStatus == STATUS_CONNECTING) {
                connectionStatus = STATUS_DISCONNECTED
            }
            printerOnlineStatus = ONLINE_OFFLINE
            printerHealthCheckedAt = timestamp()
            return
        }

        if (selectedPrinterAddress.isBlank()) {
            if (connectionStatus == STATUS_CONNECTED || connectionStatus == STATUS_CONNECTING) {
                connectionStatus = STATUS_DISCONNECTED
            }
            printerOnlineStatus = ONLINE_UNKNOWN
            return
        }

        if (!hasBluetoothConnectPermission()) {
            printerOnlineStatus = ONLINE_UNKNOWN
            return
        }

        if (selectedProfile == DirectLoopPrinterProfile.CHITENG_S1_OFFICIAL) {
            if (connectionStatus == STATUS_DISCONNECTED && !officialSdkConnected) {
                printerOnlineStatus = ONLINE_UNKNOWN
                return
            }

            val health = chitengOfficialPrinterClient.verifyConnection(selectedPrinterAddress)
            printerHealthCheckedAt = timestamp()
            officialSdkAvailable = health.sdkAvailable
            officialSdkConnected = health.sdkConnected
            officialSdkLastMessage = health.message
            officialSdkLastError = health.error
            printerOnlineStatus = health.onlineStatus

            when (health.onlineStatus) {
                ONLINE_ONLINE -> {
                    connectionStatus = STATUS_CONNECTED
                    lastError = ""
                }

                ONLINE_OFFLINE -> {
                    connectionStatus = STATUS_DISCONNECTED
                    lastError = health.error.ifBlank {
                        health.message.ifBlank { PRINTER_NOT_RESPONDING_MESSAGE }
                    }
                }

                ONLINE_ERROR -> {
                    connectionStatus = STATUS_ERROR
                    lastError = health.error.ifBlank {
                        health.message.ifBlank { "Printer status query failed." }
                    }
                }

                else -> {
                    if (!health.sdkConnected && connectionStatus == STATUS_CONNECTED) {
                        connectionStatus = STATUS_DISCONNECTED
                    }
                }
            }
            return
        }

        val socketConnected = socket?.isConnected == true && outputStream != null
        if (connectionStatus == STATUS_CONNECTED && !socketConnected) {
            connectionStatus = STATUS_DISCONNECTED
            printerOnlineStatus = ONLINE_OFFLINE
            printerHealthCheckedAt = timestamp()
        } else if (connectionStatus == STATUS_CONNECTED) {
            printerOnlineStatus = ONLINE_UNKNOWN
            printerHealthCheckedAt = timestamp()
        } else if (connectionStatus == STATUS_DISCONNECTED) {
            printerOnlineStatus = ONLINE_UNKNOWN
        }
    }

    private fun refreshUrovoPrinterHealth() {
        val result = urovoK300PrinterManagerClient.getStatus()
        applyUrovoResult(result)
        lastPreviewTransport = lastPreviewTransport.ifBlank { PREVIEW_TRANSPORT_UROVO_PRINTER_MANAGER }
        if (lastProtocolTested.startsWith("UROVO_K300") || lastProtocolTested.isBlank()) {
            lastPreviewSdkOperations = result.operations
        }
        lastError = if (result.success) "" else result.message
    }

    private fun refreshExternalK300SppHealth() {
        printerHealthCheckedAt = timestamp()
        printerOnlineStatus = ONLINE_UNKNOWN
        if (connectionStatus == STATUS_CONNECTING) {
            connectionStatus = if (k300SppAvailable) STATUS_CONNECTED else STATUS_DISCONNECTED
        }
        if (lastProtocolTested.isBlank() && k300SppLastCheckedAt.isNotBlank()) {
            lastProtocolTested = K300_SPP_CONNECT_TEST_PROTOCOL
        }
    }

    private fun applyUrovoResult(result: UrovoK300PrinterManagerClient.UrovoK300PrintResult) {
        urovoPrinterAvailable = result.available
        urovoLastStatusCode = result.statusCode
        urovoLastStatusText = result.statusText
        printerHealthCheckedAt = timestamp()

        if (!result.available) {
            connectionStatus = STATUS_ERROR
            printerOnlineStatus = ONLINE_UNKNOWN
            return
        }

        when (result.statusText) {
            "ok" -> {
                connectionStatus = STATUS_CONNECTED
                printerOnlineStatus = ONLINE_ONLINE
            }

            "busy" -> {
                connectionStatus = STATUS_CONNECTED
                printerOnlineStatus = ONLINE_UNKNOWN
            }

            "out_of_paper",
            "over_heat",
            "under_voltage",
            "error",
            "driver_error" -> {
                connectionStatus = STATUS_ERROR
                printerOnlineStatus = ONLINE_ERROR
            }

            else -> {
                connectionStatus = if (result.success) STATUS_CONNECTED else STATUS_ERROR
                printerOnlineStatus = ONLINE_UNKNOWN
            }
        }
    }

    private fun syncOfficialSdkSummary() {
        officialSdkAvailable = chitengOfficialPrinterClient.isSdkAvailable()
        officialSdkConnected = chitengOfficialPrinterClient.isOfficialSdkConnected()
        officialSdkLastMessage = chitengOfficialPrinterClient.lastSdkMessage()
        officialSdkLastError = chitengOfficialPrinterClient.lastSdkError()
    }

    private fun parsePrinterSelection(configOrAddress: String): PrinterSelection {
        val value = configOrAddress.trim()
        if (!value.startsWith("{")) {
            return PrinterSelection(
                profile = selectedProfile,
                address = value,
                name = selectedPrinterName,
            )
        }

        val json = JSONObject(value)
        return PrinterSelection(
            profile = DirectLoopPrinterProfile.from(json.optString("profile")),
            address = json.optString("address").trim(),
            name = json.optString("name").trim(),
        )
    }

    private fun bluetoothAdapter(): BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private fun isBluetoothEnabled(adapter: BluetoothAdapter): Boolean {
        return try {
            adapter.isEnabled
        } catch (_: SecurityException) {
            false
        }
    }

    private fun ensureBluetoothConnectPermission(): Boolean {
        if (hasBluetoothConnectPermission()) return true

        activity.runOnUiThread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activity.requestPermissions(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN,
                    ),
                    BLUETOOTH_PERMISSION_REQUEST_CODE,
                )
            }
        }
        lastError = "Bluetooth permission is required. Grant Nearby devices permission and retry."
        return false
    }

    private fun ensureBluetoothDiscoveryPermission(): Boolean {
        if (hasBluetoothDiscoveryPermission()) return true

        activity.runOnUiThread {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    activity.requestPermissions(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN,
                        ),
                        BLUETOOTH_PERMISSION_REQUEST_CODE,
                    )
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    activity.requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        BLUETOOTH_PERMISSION_REQUEST_CODE,
                    )
                }
            }
        }
        lastError = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "Bluetooth permission is required. Grant Nearby devices permission and retry."
        } else {
            "Bluetooth discovery location permission is required. Grant Location permission and retry."
        }
        discoveryStatus = DISCOVERY_ERROR
        return false
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothDiscoveryPermission(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                hasBluetoothConnectPermission() && hasBluetoothScanPermission()

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

            else -> true
        }
    }

    private fun permissionError(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (!hasBluetoothConnectPermission() || !hasBluetoothScanPermission())) {
            "Bluetooth permission is required. Grant Nearby devices permission and retry."
        } else {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun pairedPrinterArray(adapter: BluetoothAdapter?): JSONArray {
        val devices = try {
            adapter?.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            return JSONArray()
        }

        return JSONArray().apply {
            devices
                .sortedWith(compareBy({ safeDeviceName(it).lowercase(Locale.US) }, { safeDeviceAddress(it) }))
                .forEach { device ->
                    put(
                        JSONObject()
                            .put("name", safeDeviceName(device))
                            .put("address", safeDeviceAddress(device))
                            .put("bond_state", safeBondState(device))
                            .put("device_type", deviceType(device))
                            .put("source", SOURCE_PAIRED)
                            .put("online_status", ONLINE_UNKNOWN),
                    )
                }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectSocket(
        adapter: BluetoothAdapter,
        selection: PrinterSelection,
        bondedDevice: BluetoothDevice,
    ) {
        val device = adapter.getRemoteDevice(selection.address)

        if (selection.name.isBlank()) {
            selectedPrinterName = bondedDevice.name.orEmpty()
        }

        if (hasBluetoothScanPermission()) {
            try {
                adapter.cancelDiscovery()
            } catch (_: SecurityException) {
                // Connecting can continue even if discovery cancellation is denied.
            }
        }

        val connectedSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        connectedSocket.connect()
        socket = connectedSocket
        outputStream = connectedSocket.outputStream
    }

    @SuppressLint("MissingPermission")
    private fun writeOneShotSppBytes(
        adapter: BluetoothAdapter,
        bondedDevice: BluetoothDevice,
        bytes: ByteArray,
        sleepMs: Long = 400L,
    ) {
        if (hasBluetoothScanPermission()) {
            try {
                adapter.cancelDiscovery()
            } catch (_: SecurityException) {
                // The one-shot SPP attempt can continue even if discovery cancellation is denied.
            }
        }

        val device = adapter.getRemoteDevice(safeDeviceAddress(bondedDevice))
        val diagnosticSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            diagnosticSocket.connect()
            val stream = diagnosticSocket.outputStream
            stream.write(bytes)
            stream.flush()
            Thread.sleep(sleepMs)
        } finally {
            try {
                diagnosticSocket.close()
            } catch (_: IOException) {
                // Closing a diagnostic socket is best-effort.
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun bondedDeviceForAddress(adapter: BluetoothAdapter, address: String): BluetoothDevice? {
        return try {
            adapter.bondedDevices.firstOrNull { it.address == address }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun registerDiscoveryReceiver() {
        if (discoveryReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            activity.registerReceiver(discoveryReceiver, filter)
        }
        discoveryReceiverRegistered = true
    }

    private fun unregisterDiscoveryReceiver() {
        if (!discoveryReceiverRegistered) return
        try {
            activity.unregisterReceiver(discoveryReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver may already be unregistered during Activity teardown.
        }
        discoveryReceiverRegistered = false
    }

    private fun stopPrinterDiscoveryInternal(
        adapter: BluetoothAdapter?,
        nextStatus: String,
    ) {
        mainHandler.removeCallbacks(discoveryTimeoutRunnable)
        cancelBluetoothDiscovery(adapter)
        unregisterDiscoveryReceiver()
        discoveryStatus = nextStatus
    }

    private fun finishPrinterDiscovery() {
        mainHandler.removeCallbacks(discoveryTimeoutRunnable)
        unregisterDiscoveryReceiver()
        if (discoveryStatus == DISCOVERY_SEARCHING) {
            discoveryStatus = DISCOVERY_FINISHED
        }
    }

    @SuppressLint("MissingPermission")
    private fun cancelBluetoothDiscovery(adapter: BluetoothAdapter?) {
        if (adapter == null || !hasBluetoothScanPermission()) return
        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
        } catch (_: SecurityException) {
            lastError = "Bluetooth permission denied while stopping printer discovery."
            discoveryStatus = DISCOVERY_ERROR
        }
    }

    private fun seedPairedPrinters(adapter: BluetoothAdapter) {
        bondedDevices(adapter).forEach { device ->
            recordDiscoveredDevice(device, rssi = null, source = SOURCE_PAIRED)
        }
    }

    @SuppressLint("MissingPermission")
    private fun bondedDevices(adapter: BluetoothAdapter?): Set<BluetoothDevice> {
        return try {
            adapter?.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            emptySet()
        }
    }

    private fun recordDiscoveredDevice(
        device: BluetoothDevice?,
        rssi: Int?,
        source: String = SOURCE_DISCOVERED,
    ) {
        val printer = discoveredPrinter(device, rssi, source) ?: return
        discoveredPrintersByAddress[printer.address] = printer
    }

    private fun discoveredPrinterArray(adapter: BluetoothAdapter?): JSONArray {
        val merged = linkedMapOf<String, DiscoveredPrinter>()
        bondedDevices(adapter)
            .sortedWith(compareBy({ safeDeviceName(it).lowercase(Locale.US) }, { safeDeviceAddress(it) }))
            .forEach { device ->
                discoveredPrinter(device, rssi = null, source = SOURCE_PAIRED)?.let { printer ->
                    merged[printer.address] = printer
                }
            }
        discoveredPrintersByAddress.values.forEach { printer ->
            merged[printer.address] = printer
        }

        return JSONArray().apply {
            merged.values
                .sortedWith(compareBy({ it.name.lowercase(Locale.US) }, { it.address }))
                .forEach { printer -> put(printer.toJson()) }
        }
    }

    private fun discoveredPrinter(
        device: BluetoothDevice?,
        rssi: Int?,
        source: String,
    ): DiscoveredPrinter? {
        device ?: return null
        val address = safeDeviceAddress(device)
        if (address.isBlank()) return null
        return DiscoveredPrinter(
            name = safeDeviceName(device),
            address = address,
            bondState = safeBondState(device),
            deviceType = deviceType(device),
            rssi = rssi,
            source = source,
        )
    }

    private fun bluetoothDeviceFromIntent(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun safeDeviceName(device: BluetoothDevice): String {
        return try {
            device.name.orEmpty()
        } catch (_: SecurityException) {
            ""
        }
    }

    private fun safeDeviceAddress(device: BluetoothDevice): String {
        return try {
            device.address.orEmpty()
        } catch (_: SecurityException) {
            ""
        }
    }

    private fun safeBondState(device: BluetoothDevice): String {
        return try {
            bondState(device.bondState)
        } catch (_: SecurityException) {
            "unknown"
        }
    }

    private fun closeSocket() {
        outputStream = null
        socket?.let { currentSocket ->
            try {
                currentSocket.close()
            } catch (_: IOException) {
                // Closing a failed Bluetooth socket is best-effort.
            }
        }
        socket = null
        if (connectionStatus != STATUS_ERROR) {
            connectionStatus = STATUS_DISCONNECTED
        }
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    }

    private fun bondState(state: Int): String {
        return when (state) {
            BluetoothDevice.BOND_BONDED -> "bonded"
            BluetoothDevice.BOND_BONDING -> "bonding"
            BluetoothDevice.BOND_NONE -> "none"
            else -> state.toString()
        }
    }

    private fun deviceType(device: BluetoothDevice): String {
        return try {
            when (device.type) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
                BluetoothDevice.DEVICE_TYPE_LE -> "le"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
                BluetoothDevice.DEVICE_TYPE_UNKNOWN -> "unknown"
                else -> device.type.toString()
            }
        } catch (_: SecurityException) {
            "unknown"
        }
    }

    private data class PrinterSelection(
        val profile: DirectLoopPrinterProfile,
        val address: String,
        val name: String,
    )

    private data class PreviewPrintTarget(
        val adapter: BluetoothAdapter,
        val bondedDevice: BluetoothDevice,
    )

    private data class K300CpclRawBatchPayload(
        val batchName: String,
        val labels: List<K300CpclRawBatchLabel>,
    )

    private data class K300CpclRawBatchLabel(
        val testName: String,
        val cpclCommand: String,
        val bytes: ByteArray,
    )

    private data class DiscoveredPrinter(
        val name: String,
        val address: String,
        val bondState: String,
        val deviceType: String,
        val rssi: Int?,
        val source: String,
    ) {
        fun toJson(): JSONObject {
            val json = JSONObject()
                .put("name", name)
                .put("address", address)
                .put("bond_state", bondState)
                .put("device_type", deviceType)
                .put("source", source)
                .put("online_status", ONLINE_UNKNOWN)
            if (rssi != null) {
                json.put("rssi", rssi)
            }
            return json
        }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val BLUETOOTH_PERMISSION_REQUEST_CODE = 1104
        private const val DISCOVERY_TIMEOUT_MS = 15000L
        private const val DISCOVERY_IDLE = "idle"
        private const val DISCOVERY_SEARCHING = "searching"
        private const val DISCOVERY_FINISHED = "finished"
        private const val DISCOVERY_ERROR = "error"
        private const val STATUS_DISCONNECTED = "disconnected"
        private const val STATUS_CONNECTING = "connecting"
        private const val STATUS_CONNECTED = "connected"
        private const val STATUS_ERROR = "error"
        private const val ONLINE_ONLINE = "online"
        private const val ONLINE_OFFLINE = "offline"
        private const val ONLINE_UNKNOWN = "unknown"
        private const val ONLINE_ERROR = "error"
        private const val APP_INFO_BRIDGE_VERSION = "pda-android-20260510-appinfo"
        private const val SOURCE_PAIRED = "paired"
        private const val SOURCE_DISCOVERED = "discovered"
        private const val PRINTER_NOT_RESPONDING_MESSAGE = "Printer is not responding. Turn on the printer and reconnect."
        private const val STORE_ITEM_LABEL_PREVIEW_CTPL_NO_LABEL_MODE_PROTOCOL = "STORE_ITEM_LABEL_PREVIEW_CTPL_NO_LABEL_MODE"
        private const val STORE_ITEM_LABEL_PREVIEW_CTPL_BITMAP_DEMO_PROTOCOL = "STORE_ITEM_LABEL_PREVIEW_CTPL_BITMAP_DEMO"
        private const val STORE_ITEM_LABEL_PREVIEW_TSPL_PROTOCOL = "STORE_ITEM_LABEL_PREVIEW_TSPL"
        private const val S1_RAW_TSPL_MIN_TEXT_PROTOCOL = "S1_RAW_TSPL_MIN_TEXT"
        private const val S1_RAW_TSPL_BLACK_BOX_PROTOCOL = "S1_RAW_TSPL_BLACK_BOX"
        private const val UROVO_K300_MIN_TEXT_PROTOCOL = "UROVO_K300_MIN_TEXT"
        private const val UROVO_K300_BLACK_BOX_PROTOCOL = "UROVO_K300_BLACK_BOX"
        private const val UROVO_K300_STORE_ITEM_PREVIEW_PROTOCOL = "UROVO_K300_STORE_ITEM_PREVIEW"
        private const val K300_ESCPOS_MIN_TEXT_PROTOCOL = "K300_ESCPOS_MIN_TEXT"
        private const val K300_CPCL_MIN_TEXT_PROTOCOL = "K300_CPCL_MIN_TEXT"
        private const val K300_CPCL_CODE128_TEST_PROTOCOL = "K300_CPCL_CODE128_TEST"
        private const val K300_CPCL_CODE128_WIDE_TEST_PROTOCOL = "K300_CPCL_CODE128_WIDE_TEST"
        private const val K300_CPCL_CODE128_TALL_TEST_PROTOCOL = "K300_CPCL_CODE128_TALL_TEST"
        private const val K300_CPCL_CODE128_QUIET_ZONE_TEST_PROTOCOL = "K300_CPCL_CODE128_QUIET_ZONE_TEST"
        private const val K300_CPCL_CODE128_COMPACT_TOP_TEST_PROTOCOL = "K300_CPCL_CODE128_COMPACT_TOP_TEST"
        private const val K300_CPCL_RAW_PREVIEW_PROTOCOL = "K300_CPCL_RAW_PREVIEW"
        private const val K300_CPCL_RAW_BATCH_PROTOCOL = "K300_CPCL_RAW_BATCH"
        private const val K300_CPCL_STORE_ITEM_PREVIEW_PROTOCOL = "K300_CPCL_STORE_ITEM_PREVIEW"
        private const val K300_TSPL_MIN_TEXT_PROTOCOL = "K300_TSPL_MIN_TEXT"
        private const val K300_TSPL_BLACK_BOX_PROTOCOL = "K300_TSPL_BLACK_BOX"
        private const val K300_SPP_CONNECT_TEST_PROTOCOL = "K300_SPP_CONNECT_TEST"
        private const val PREVIEW_TRANSPORT_CTPL_SDK_NO_LABEL_MODE = "CTPL_SDK_NO_LABEL_MODE"
        private const val PREVIEW_TRANSPORT_CTPL_SDK_BITMAP_DEMO = "CTPL_SDK_BITMAP_DEMO"
        private const val PREVIEW_TRANSPORT_RAW_TSPL_SPP = "RAW_TSPL_SPP"
        private const val PREVIEW_TRANSPORT_UROVO_PRINTER_MANAGER = "UROVO_PRINTER_MANAGER"
        private const val PREVIEW_TRANSPORT_K300_BLUETOOTH_SPP = "K300_BLUETOOTH_SPP"
        private const val PREVIEW_PRINT_BUSY_WINDOW_MS = 8000L
        private const val RESULT_NONE = "none"
        private const val RESULT_SUCCESS = "success"
        private const val RESULT_FAILED = "failed"
        private val ESC_INIT_BYTES = byteArrayOf(0x1B, 0x40)
        private val K300_SPP_CONNECT_TEST_OPERATIONS = listOf(
            "open_spp_socket",
            "write_esc_init",
            "flush",
            "close_spp_socket",
        )
    }
}
