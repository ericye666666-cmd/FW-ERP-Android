package com.directloop.pda

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
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
    private var lastError = ""
    private var lastProtocolTested = ""
    private var lastPrintResult = RESULT_NONE
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

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

        closeSocket()
        selectedPrinterAddress = selection.address
        selectedPrinterName = selection.name
        selectedProfile = selection.profile
        connectionStatus = STATUS_CONNECTING
        lastError = ""

        return try {
            connectSocket(adapter, selection)
            connectionStatus = STATUS_CONNECTED
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
        closeSocket()
        connectionStatus = STATUS_DISCONNECTED
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
    fun getLastPrintResult(): String {
        return guardedStatus().toString()
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
        return statusJson(bridgeAvailable = true, errorOverride = message)
    }

    private fun statusJson(
        bridgeAvailable: Boolean,
        errorOverride: String? = null,
    ): JSONObject {
        val adapter = bluetoothAdapter()
        val bluetoothEnabled = adapter?.let { isBluetoothEnabled(it) } ?: false
        val pairedPrinters = if (bridgeAvailable && bluetoothEnabled && hasBluetoothConnectPermission()) {
            pairedPrinterArray(adapter)
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
            .put("paired_printer_count", pairedPrinters.length())
            .put("paired_printers", pairedPrinters)
            .put("selected_printer_name", selectedPrinterName)
            .put("selected_printer_address", selectedPrinterAddress)
            .put("selected_profile", selectedProfile.name)
            .put("connection_status", connectionStatus)
            .put("last_error", statusError)
            .put("last_protocol_tested", lastProtocolTested)
            .put("last_print_result", lastPrintResult)
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

    private fun hasBluetoothConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    }

    private fun permissionError(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothConnectPermission()) {
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
                .sortedBy { it.name.orEmpty().lowercase(Locale.US) }
                .forEach { device ->
                    put(
                        JSONObject()
                            .put("name", device.name.orEmpty())
                            .put("address", device.address.orEmpty())
                            .put("bond_state", bondState(device.bondState)),
                    )
                }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectSocket(adapter: BluetoothAdapter, selection: PrinterSelection) {
        val device = adapter.getRemoteDevice(selection.address)
        val bondedDevice = adapter.bondedDevices.firstOrNull { it.address == selection.address }
        if (bondedDevice == null) {
            throw IOException("selected printer is not paired")
        }

        if (selection.name.isBlank()) {
            selectedPrinterName = bondedDevice.name.orEmpty()
        }

        if (hasBluetoothScanPermission()) {
            adapter.cancelDiscovery()
        }

        val connectedSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        connectedSocket.connect()
        socket = connectedSocket
        outputStream = connectedSocket.outputStream
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

    private data class PrinterSelection(
        val profile: DirectLoopPrinterProfile,
        val address: String,
        val name: String,
    )

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val BLUETOOTH_PERMISSION_REQUEST_CODE = 1104
        private const val STATUS_DISCONNECTED = "disconnected"
        private const val STATUS_CONNECTING = "connecting"
        private const val STATUS_CONNECTED = "connected"
        private const val STATUS_ERROR = "error"
        private const val RESULT_NONE = "none"
        private const val RESULT_SUCCESS = "success"
        private const val RESULT_FAILED = "failed"
    }
}
