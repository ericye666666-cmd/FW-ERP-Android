package com.directloop.pda

import android.app.Application
import android.graphics.Paint
import android.graphics.Point
import com.ctaiot.ctprinter.ctpl.CTPL
import com.ctaiot.ctprinter.ctpl.Device
import com.ctaiot.ctprinter.ctpl.RespCallback
import com.ctaiot.ctprinter.ctpl.param.BarCode
import com.ctaiot.ctprinter.ctpl.param.PaperType
import com.ctaiot.ctprinter.ctpl.param.PrintMode
import com.ctaiot.ctprinter.ctpl.param.Rotate
import java.util.HashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChitengS1OfficialPrinterClient(
    private val application: Application,
) {
    private val callbackLock = Object()
    private var initialized = false
    private var connectedAddress = ""
    private var pendingConnectLatch: CountDownLatch? = null
    private var lastConnectPort = UNKNOWN_CONNECT_CODE
    private var lastConnectReason = UNKNOWN_CONNECT_CODE
    private var lastDataResponse: Map<String, String> = emptyMap()

    fun isSdkAvailable(): Boolean {
        return runCatching { CTPL.getInstance() }.isSuccess
    }

    @Synchronized
    fun connect(address: String, name: String): ChitengOfficialPrintResult {
        val printerAddress = address.trim()
        if (printerAddress.isBlank()) {
            return ChitengOfficialPrintResult.failure("Chiteng official SDK connect requires a Bluetooth printer address.")
        }

        val sdk = sdkOrFailure() ?: return ChitengOfficialPrintResult.failure("Chiteng official CTPL SDK is not available.")
        initializeSdk(sdk)

        return try {
            if (sdk.isConnected && connectedAddress == printerAddress) {
                return ChitengOfficialPrintResult.success("Chiteng official SDK is already connected.")
            }
            if (sdk.isConnected && connectedAddress != printerAddress) {
                sdk.disconnect()
                connectedAddress = ""
            }

            val latch = CountDownLatch(1)
            synchronized(callbackLock) {
                lastConnectPort = UNKNOWN_CONNECT_CODE
                lastConnectReason = UNKNOWN_CONNECT_CODE
                pendingConnectLatch = latch
            }

            val device = Device().apply {
                setPort(CTPL.Port.SPP)
                setBluetoothMacAddr(printerAddress)
                if (name.isNotBlank()) {
                    setName(name)
                }
            }

            sdk.connect(device)
            val callbackReceived = latch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            val reason = synchronized(callbackLock) {
                pendingConnectLatch = null
                lastConnectReason
            }

            if (!callbackReceived && !sdk.isConnected) {
                return ChitengOfficialPrintResult.failure("Chiteng official SDK connection timed out.")
            }
            if (isConnectSuccess(reason) || sdk.isConnected) {
                connectedAddress = printerAddress
                ChitengOfficialPrintResult.success("Chiteng official SDK connected.")
            } else {
                ChitengOfficialPrintResult.failure(connectReasonMessage(reason))
            }
        } catch (error: SecurityException) {
            ChitengOfficialPrintResult.failure("Bluetooth permission denied while connecting through Chiteng official SDK.")
        } catch (error: RuntimeException) {
            ChitengOfficialPrintResult.failure("Chiteng official SDK connection failed: ${error.message ?: "unknown error"}.")
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            ChitengOfficialPrintResult.failure("Chiteng official SDK connection was interrupted.")
        } finally {
            synchronized(callbackLock) {
                pendingConnectLatch = null
            }
        }
    }

    @Synchronized
    fun printOfficialDiagnosticLabel(
        address: String,
        name: String,
    ): ChitengOfficialPrintResult {
        val connectResult = connect(address, name)
        if (!connectResult.success) return connectResult

        val sdk = sdkOrFailure() ?: return ChitengOfficialPrintResult.failure("Chiteng official CTPL SDK is not available.")

        return try {
            sdk.clean()
            sdk.setBackpressure(true)
            sdk
                .setSize(60, 40)
                .setPaperType(PaperType.Label)
                .setPrintMode(PrintMode.Label_Divide)
                .setPrintSpeed(2)
                .setPrintDensity(12)
                .drawText(Point(24, 24), Rotate.Degree0, 1, 1, "DIRECT LOOP")
                .drawText(Point(24, 58), Rotate.Degree0, 1, 1, "CHITENG S1 OFFICIAL")
                .drawText(Point(24, 92), Rotate.Degree0, 1, 1, "SDK TEST")
                .drawText(Point(330, 24), Rotate.Degree0, 1, 1, "KES 450")
                .drawText(Point(24, 126), Rotate.Degree0, 1, 1, TEST_CODE)
                .drawBarCode(
                    Point(34, 168),
                    78,
                    BarCode.CODE_128,
                    Paint.Align.LEFT,
                    Rotate.Degree0,
                    2,
                    2,
                    TEST_CODE,
                )
                .print(1)
                .execute()

            ChitengOfficialPrintResult.success("Chiteng official SDK diagnostic label was sent.")
        } catch (error: SecurityException) {
            ChitengOfficialPrintResult.failure("Bluetooth permission denied while printing through Chiteng official SDK.")
        } catch (error: RuntimeException) {
            ChitengOfficialPrintResult.failure("Chiteng official SDK diagnostic print failed: ${error.message ?: "unknown error"}.")
        }
    }

    @Synchronized
    fun disconnect() {
        runCatching {
            if (initialized) {
                val sdk = CTPL.getInstance()
                if (sdk.isConnected) {
                    sdk.disconnect()
                }
            }
        }
        connectedAddress = ""
    }

    fun lastStatusSummary(): String {
        val data = synchronized(callbackLock) { lastDataResponse }
        return if (data.isEmpty()) "" else data.entries.joinToString(", ") { "${it.key}=${it.value}" }
    }

    private fun initializeSdk(sdk: CTPL) {
        if (initialized) return

        sdk.init(
            application,
            object : RespCallback {
                override fun onConnectRespsonse(port: Int, reason: Int) {
                    synchronized(callbackLock) {
                        lastConnectPort = port
                        lastConnectReason = reason
                        pendingConnectLatch?.countDown()
                    }
                }

                override fun onDataResponse(result: HashMap<String, String>) {
                    synchronized(callbackLock) {
                        lastDataResponse = result.toMap()
                    }
                }

                override fun autoSPPBond(): Boolean = false
            },
        )
        initialized = true
    }

    private fun sdkOrFailure(): CTPL? {
        return runCatching { CTPL.getInstance() }.getOrNull()
    }

    private fun isConnectSuccess(reason: Int): Boolean {
        return reason == CONNECTED_BLE || reason == CONNECTED_SPP || reason == CONNECTED_USB
    }

    private fun connectReasonMessage(reason: Int): String {
        return when (reason) {
            CONNECTED_BLE -> "Chiteng official SDK connected over BLE."
            CONNECTED_SPP -> "Chiteng official SDK connected over SPP."
            CONNECTED_USB -> "Chiteng official SDK connected over USB."
            512 -> "Chiteng official SDK connection failed: unsupported connection type."
            513 -> "Chiteng official SDK connection failed: connection is already in progress."
            514 -> "Chiteng official SDK connection failed: printer is already connected."
            515 -> "Chiteng official SDK connection failed: invalid printer configuration."
            516 -> "Chiteng official SDK connection failed: insufficient Bluetooth permission."
            517 -> "Chiteng official SDK connection failed: Android Bluetooth system error."
            518 -> "Chiteng official SDK connection failed: SPP pairing failed. Pair the printer in Android Bluetooth settings first."
            519 -> "Chiteng official SDK connection failed: BLE service mismatch."
            UNKNOWN_CONNECT_CODE -> "Chiteng official SDK connection failed without a callback reason."
            else -> "Chiteng official SDK connection failed with reason code $reason."
        }
    }

    data class ChitengOfficialPrintResult(
        val success: Boolean,
        val message: String,
    ) {
        companion object {
            fun success(message: String): ChitengOfficialPrintResult {
                return ChitengOfficialPrintResult(success = true, message = message)
            }

            fun failure(message: String): ChitengOfficialPrintResult {
                return ChitengOfficialPrintResult(success = false, message = message)
            }
        }
    }

    companion object {
        private const val TEST_CODE = "TEST123456"
        private const val CONNECT_TIMEOUT_MS = 8000L
        private const val UNKNOWN_CONNECT_CODE = -1
        private const val CONNECTED_BLE = 256
        private const val CONNECTED_SPP = 257
        private const val CONNECTED_USB = 258
    }
}
