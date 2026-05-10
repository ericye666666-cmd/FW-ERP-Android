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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class DirectLoopPrinterProfile {
    CHITENG_S1,
    CHITENG_S1_OFFICIAL,
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
    private var lastError = ""
    private var lastProtocolTested = ""
    private var lastPrintResult = RESULT_NONE
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val chitengOfficialPrinterClient = ChitengS1OfficialPrinterClient(activity.application)
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
        if (!isTrustedPage()) return untrustedStatus().toString()

        lastProtocolTested = STORE_ITEM_LABEL_PREVIEW_PROTOCOL
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

        if (selectedProfile != DirectLoopPrinterProfile.CHITENG_S1_OFFICIAL) {
            return previewPrintFailure("STORE_ITEM preview labels require CHITENG_S1_OFFICIAL printer profile.")
                .toString()
        }

        return printOfficialStoreItemLabelPreview(payload)
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

    private fun printOfficialStoreItemLabelPreview(
        payload: ChitengS1OfficialPrinterClient.StoreItemLabelPreviewPayload,
    ): String {
        if (selectedPrinterAddress.isBlank()) {
            return previewPrintFailure(
                "No Bluetooth printer is selected. Select a paired Chiteng S1 printer before printing STORE_ITEM preview labels.",
            ).toString()
        }

        val adapter = bluetoothAdapter()
            ?: return previewPrintFailure("Bluetooth adapter is not available on this device.").toString()
        if (!ensureBluetoothConnectPermission()) return statusJson(bridgeAvailable = true, refreshHealth = false).toString()
        if (!isBluetoothEnabled(adapter)) {
            return previewPrintFailure("Bluetooth is disabled. Enable Bluetooth before printing STORE_ITEM preview labels.").toString()
        }
        if (!chitengOfficialPrinterClient.isSdkAvailable()) {
            return previewPrintFailure("Chiteng official CTPL SDK is not available in this build.").toString()
        }
        if (bondedDeviceForAddress(adapter, selectedPrinterAddress) == null) {
            return previewPrintFailure("请先在 Android 系统蓝牙中完成配对后再连接。").toString()
        }

        closeSocket()
        connectionStatus = STATUS_CONNECTING

        val result = chitengOfficialPrinterClient.printStoreItemLabelPreview(
            address = selectedPrinterAddress,
            name = selectedPrinterName,
            payload = payload,
        )

        return if (result.success) {
            connectionStatus = STATUS_CONNECTED
            lastPrintResult = RESULT_SUCCESS
            lastError = ""
            syncOfficialSdkSummary()
            statusJson(bridgeAvailable = true, errorOverride = "", refreshHealth = false).toString()
        } else {
            connectionStatus = STATUS_ERROR
            previewPrintFailure(result.message).toString()
        }
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
            ?: permissionError()
            ?: when {
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
            .put("last_error", statusError)
            .put("last_protocol_tested", lastProtocolTested)
            .put("last_print_result", lastPrintResult)
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
        private const val SOURCE_PAIRED = "paired"
        private const val SOURCE_DISCOVERED = "discovered"
        private const val PRINTER_NOT_RESPONDING_MESSAGE = "Printer is not responding. Turn on the printer and reconnect."
        private const val STORE_ITEM_LABEL_PREVIEW_PROTOCOL = "STORE_ITEM_LABEL_PREVIEW"
        private const val RESULT_NONE = "none"
        private const val RESULT_SUCCESS = "success"
        private const val RESULT_FAILED = "failed"
    }
}
