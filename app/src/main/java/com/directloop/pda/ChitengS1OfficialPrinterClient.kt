package com.directloop.pda

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import com.ctaiot.ctprinter.ctpl.CTPL
import com.ctaiot.ctprinter.ctpl.Device
import com.ctaiot.ctprinter.ctpl.RespCallback
import com.ctaiot.ctprinter.ctpl.param.BarCode
import com.ctaiot.ctprinter.ctpl.param.PaperType
import com.ctaiot.ctprinter.ctpl.param.PrintMode
import com.ctaiot.ctprinter.ctpl.param.Rotate
import org.json.JSONException
import org.json.JSONObject
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val STORE_ITEM_PREVIEW_TEMPLATE_60X40 = "60x40"
private const val STORE_ITEM_PREVIEW_TEMPLATE_40X30 = "40x30"
private const val STORE_ITEM_PREVIEW_PRINT_MODE = "preview_one"
private const val STORE_ITEM_PREVIEW_DEBUG_COORDINATE_TEST = "coordinate_test"
private const val STORE_ITEM_LABEL_DOTS_PER_MM = 8
private val STORE_ITEM_MACHINE_CODE_PATTERN = Regex("^\\d{8,32}$")
private val CODE_128_PATTERNS = arrayOf(
    "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212",
    "221213", "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221",
    "223211", "221132", "221231", "213212", "223112", "312131", "311222", "321122", "321221",
    "312212", "322112", "322211", "212123", "212321", "232121", "111323", "131123", "131321",
    "112313", "132113", "132311", "211313", "231113", "231311", "112133", "112331", "132131",
    "113123", "113321", "133121", "313121", "211331", "231131", "213113", "213311", "213131",
    "311123", "311321", "331121", "312113", "312311", "332111", "314111", "221411", "431111",
    "111224", "111422", "121124", "121421", "141122", "141221", "112214", "112412", "122114",
    "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111", "111242",
    "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
    "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311",
    "113141", "114131", "311141", "411131", "211412", "211214", "211232", "2331112",
)

class ChitengS1OfficialPrinterClient(
    private val application: Application,
) {
    private val callbackLock = Object()
    private var initialized = false
    private var connectedAddress = ""
    private var pendingConnectLatch: CountDownLatch? = null
    private var pendingHealthLatch: CountDownLatch? = null
    private var lastConnectPort = UNKNOWN_CONNECT_CODE
    private var lastConnectReason = UNKNOWN_CONNECT_CODE
    private var lastDataResponse: Map<String, String> = emptyMap()
    private var lastMessage = ""
    private var lastError = ""

    fun isSdkAvailable(): Boolean {
        return runCatching { CTPL.getInstance() }.isSuccess
    }

    @Synchronized
    fun connect(address: String, name: String): ChitengOfficialPrintResult {
        val printerAddress = address.trim()
        if (printerAddress.isBlank()) {
            return failure("Chiteng official SDK connect requires a Bluetooth printer address.")
        }

        val sdk = sdkOrFailure() ?: return failure("Chiteng official CTPL SDK is not available.")
        initializeSdk(sdk)

        return try {
            if (sdk.isConnected && connectedAddress == printerAddress) {
                lastMessage = "Chiteng official SDK is already connected."
                lastError = ""
                return ChitengOfficialPrintResult.success(lastMessage)
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
                return failure("Chiteng official SDK connection timed out.")
            }
            if (isConnectSuccess(reason) || sdk.isConnected) {
                connectedAddress = printerAddress
                success("Chiteng official SDK connected.")
            } else {
                failure(connectReasonMessage(reason))
            }
        } catch (error: SecurityException) {
            failure("Bluetooth permission denied while connecting through Chiteng official SDK.")
        } catch (error: RuntimeException) {
            failure("Chiteng official SDK connection failed: ${error.message ?: "unknown error"}.")
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            failure("Chiteng official SDK connection was interrupted.")
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

        val sdk = sdkOrFailure() ?: return failure("Chiteng official CTPL SDK is not available.")

        return try {
            sdk.clean()
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

            success("Chiteng official SDK diagnostic label was sent.")
        } catch (error: SecurityException) {
            failure("Bluetooth permission denied while printing through Chiteng official SDK.")
        } catch (error: RuntimeException) {
            failure("Chiteng official SDK diagnostic print failed: ${error.message ?: "unknown error"}.")
        }
    }

    @Synchronized
    fun printStoreItemLabelPreview(
        address: String,
        name: String,
        payload: StoreItemLabelPreviewPayload,
    ): ChitengOfficialPrintResult {
        val connectResult = connect(address, name)
        if (!connectResult.success) return connectResult

        val sdk = sdkOrFailure() ?: return failure("Chiteng official CTPL SDK is not available.")

        return try {
            sdk.clean()
            sdk
                .setSize(payload.widthMm, payload.heightMm)
                .setPaperType(PaperType.Label)
                .setPrintMode(PrintMode.Label_Divide)
                .setPrintSpeed(2)
                .setPrintDensity(12)
            drawStoreItemPreviewLabel(sdk, payload)
            sdk
                .print(1)
                .execute()

            success("Chiteng official SDK STORE_ITEM preview label was sent.")
        } catch (error: SecurityException) {
            failure("Bluetooth permission denied while printing STORE_ITEM preview label through Chiteng official SDK.")
        } catch (error: RuntimeException) {
            failure("Chiteng official SDK STORE_ITEM preview print failed: ${error.message ?: "unknown error"}.")
        }
    }

    @Synchronized
    fun verifyConnection(address: String): ChitengOfficialHealthResult {
        val printerAddress = address.trim()
        val sdk = sdkOrFailure()
            ?: return healthError(
                sdkAvailable = false,
                sdkConnected = false,
                message = "Chiteng official CTPL SDK is not available.",
            )
        initializeSdk(sdk)

        val sdkConnected = sdkConnectionState(sdk)
        if (printerAddress.isBlank()) {
            return ChitengOfficialHealthResult(
                sdkAvailable = true,
                sdkConnected = sdkConnected,
                onlineStatus = ONLINE_UNKNOWN,
                message = "No Chiteng S1 printer is selected.",
                error = "",
                rawStatus = emptyMap(),
            )
        }
        if (!sdkConnected) {
            connectedAddress = ""
            lastMessage = ""
            lastError = PRINTER_NOT_RESPONDING_MESSAGE
            return ChitengOfficialHealthResult(
                sdkAvailable = true,
                sdkConnected = false,
                onlineStatus = ONLINE_OFFLINE,
                message = "",
                error = lastError,
                rawStatus = emptyMap(),
            )
        }

        val latch = CountDownLatch(1)
        synchronized(callbackLock) {
            lastDataResponse = emptyMap()
            pendingHealthLatch = latch
        }

        return try {
            sdk.queryPrintState()
            sdk.execute()

            val callbackReceived = latch.await(HEALTH_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val response = synchronized(callbackLock) {
                pendingHealthLatch = null
                lastDataResponse
            }

            if (!callbackReceived || response.isEmpty()) {
                runCatching { sdk.disconnect() }
                connectedAddress = ""
                return healthError(
                    sdkAvailable = true,
                    sdkConnected = false,
                    onlineStatus = ONLINE_OFFLINE,
                    message = PRINTER_NOT_RESPONDING_MESSAGE,
                )
            }

            val problem = printerProblemMessage(response)
            if (problem != null) {
                lastMessage = statusSummary(response)
                lastError = problem
                ChitengOfficialHealthResult(
                    sdkAvailable = true,
                    sdkConnected = sdkConnectionState(sdk),
                    onlineStatus = ONLINE_ERROR,
                    message = lastMessage,
                    error = problem,
                    rawStatus = response,
                )
            } else {
                lastMessage = statusSummary(response)
                lastError = ""
                ChitengOfficialHealthResult(
                    sdkAvailable = true,
                    sdkConnected = sdkConnectionState(sdk),
                    onlineStatus = ONLINE_ONLINE,
                    message = lastMessage,
                    error = "",
                    rawStatus = response,
                )
            }
        } catch (error: SecurityException) {
            healthError(
                sdkAvailable = true,
                sdkConnected = sdkConnectionState(sdk),
                message = "Bluetooth permission denied while querying Chiteng printer status.",
            )
        } catch (error: RuntimeException) {
            runCatching { sdk.disconnect() }
            connectedAddress = ""
            healthError(
                sdkAvailable = true,
                sdkConnected = false,
                onlineStatus = ONLINE_OFFLINE,
                message = PRINTER_NOT_RESPONDING_MESSAGE,
                rawError = error.message,
            )
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            healthError(
                sdkAvailable = true,
                sdkConnected = sdkConnectionState(sdk),
                message = "Chiteng printer status query was interrupted.",
            )
        } finally {
            synchronized(callbackLock) {
                pendingHealthLatch = null
            }
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
        lastMessage = "Chiteng official SDK disconnected."
        lastError = ""
    }

    fun lastStatusSummary(): String {
        val data = synchronized(callbackLock) { lastDataResponse }
        return if (data.isEmpty()) "" else data.entries.joinToString(", ") { "${it.key}=${it.value}" }
    }

    fun isOfficialSdkConnected(): Boolean {
        val sdk = sdkOrFailure() ?: return false
        return sdkConnectionState(sdk)
    }

    fun lastSdkMessage(): String = lastMessage

    fun lastSdkError(): String = lastError

    private fun drawStoreItemPreviewLabel(
        sdk: CTPL,
        payload: StoreItemLabelPreviewPayload,
    ) {
        val bitmap = buildStoreItemPreviewBitmap(payload)
        sdk.drawBitmap(Rect(0, 0, bitmap.width, bitmap.height), bitmap, true, null)
    }

    private fun buildStoreItemPreviewBitmap(payload: StoreItemLabelPreviewPayload): Bitmap {
        if (payload.isCoordinateTest()) {
            return buildCoordinateTestBitmap(payload)
        }

        val label = payload.label
        val widthDots = payload.widthMm * STORE_ITEM_LABEL_DOTS_PER_MM
        val heightDots = payload.heightMm * STORE_ITEM_LABEL_DOTS_PER_MM
        val bitmap = Bitmap.createBitmap(widthDots, heightDots, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val regularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.MONOSPACE
        }
        val barcodePaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = false
        }

        if (payload.templateSize == STORE_ITEM_PREVIEW_TEMPLATE_40X30) {
            drawTextToFit(canvas, "${label.categoryShort} / ${label.grade}", 18f, 28f, 284f, textPaint, 22f, 16f)
            drawTextToFit(canvas, "KES ${label.priceKes}", 18f, 70f, 284f, textPaint, 36f, 24f)
            drawCode128Barcode(canvas, label.machineCode, Rect(12, 88, widthDots - 12, 178), barcodePaint)
            drawCenteredTextToFit(canvas, label.machineCode, widthDots / 2f, 218f, 296f, regularPaint, 24f, 16f)
            return bitmap
        }

        drawTextToFit(canvas, label.categoryShort, 24f, 36f, 340f, textPaint, 28f, 18f)
        drawTextToFit(canvas, label.grade, widthDots - 70f, 36f, 50f, textPaint, 28f, 18f)
        drawTextToFit(canvas, "KES ${label.priceKes}", 24f, 100f, 432f, textPaint, 52f, 32f)
        drawCode128Barcode(canvas, label.machineCode, Rect(24, 136, widthDots - 24, 244), barcodePaint)
        drawCenteredTextToFit(canvas, label.machineCode, widthDots / 2f, 282f, 416f, regularPaint, 28f, 18f)
        return bitmap
    }

    private fun buildCoordinateTestBitmap(payload: StoreItemLabelPreviewPayload): Bitmap {
        val label = payload.label
        val widthDots = payload.widthMm * STORE_ITEM_LABEL_DOTS_PER_MM
        val heightDots = payload.heightMm * STORE_ITEM_LABEL_DOTS_PER_MM
        val bitmap = Bitmap.createBitmap(widthDots, heightDots, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val strokePaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = false
        }
        val fillPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = false
        }
        val textPaint = Paint().apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 22f
            isAntiAlias = false
        }
        val monoPaint = Paint().apply {
            color = Color.BLACK
            typeface = Typeface.MONOSPACE
            textSize = 18f
            textAlign = Paint.Align.CENTER
            isAntiAlias = false
        }

        canvas.drawRect(2f, 2f, (widthDots - 3).toFloat(), (heightDots - 3).toFloat(), strokePaint)
        val markerSize = 22f
        canvas.drawRect(6f, 6f, 6f + markerSize, 6f + markerSize, fillPaint)
        canvas.drawRect(widthDots - 6f - markerSize, 6f, widthDots - 6f, 6f + markerSize, fillPaint)
        canvas.drawRect(6f, heightDots - 6f - markerSize, 6f + markerSize, heightDots - 6f, fillPaint)
        canvas.drawRect(widthDots - 6f - markerSize, heightDots - 6f - markerSize, widthDots - 6f, heightDots - 6f, fillPaint)

        canvas.drawText("TOP", 44f, 28f, textPaint)
        canvas.drawText("MID", 44f, heightDots / 2f + 7f, textPaint)
        canvas.drawText("BOT", 44f, heightDots - 16f, textPaint)
        canvas.drawText(label.machineCode, widthDots / 2f, heightDots - 42f, monoPaint)

        var x = widthDots - 116f
        val top = heightDots / 2f - 34f
        repeat(9) { index ->
            val barWidth = if (index % 2 == 0) 6f else 3f
            canvas.drawRect(x, top, x + barWidth, top + 68f, fillPaint)
            x += barWidth + 5f
        }

        return bitmap
    }

    private fun drawTextToFit(
        canvas: Canvas,
        text: String,
        x: Float,
        baselineY: Float,
        maxWidth: Float,
        paint: Paint,
        maxTextSize: Float,
        minTextSize: Float,
    ) {
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = maxTextSize
        while (paint.textSize > minTextSize && paint.measureText(text) > maxWidth) {
            paint.textSize -= 1f
        }
        canvas.drawText(text, x, baselineY, paint)
    }

    private fun drawCenteredTextToFit(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baselineY: Float,
        maxWidth: Float,
        paint: Paint,
        maxTextSize: Float,
        minTextSize: Float,
    ) {
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = maxTextSize
        while (paint.textSize > minTextSize && paint.measureText(text) > maxWidth) {
            paint.textSize -= 1f
        }
        canvas.drawText(text, centerX, baselineY, paint)
    }

    private fun drawCode128Barcode(canvas: Canvas, value: String, rect: Rect, paint: Paint) {
        val codes = buildCode128Codes(value)
        val totalModules = codes.sumOf { code ->
            CODE_128_PATTERNS[code].sumOf { width -> width.digitToInt() }
        }
        val moduleWidth = maxOf(1, rect.width() / totalModules)
        val barcodeWidth = totalModules * moduleWidth
        var x = rect.left + ((rect.width() - barcodeWidth) / 2)
        codes.forEach { code ->
            CODE_128_PATTERNS[code].forEachIndexed { index, widthChar ->
                val segmentWidth = widthChar.digitToInt() * moduleWidth
                if (index % 2 == 0) {
                    canvas.drawRect(x.toFloat(), rect.top.toFloat(), (x + segmentWidth).toFloat(), rect.bottom.toFloat(), paint)
                }
                x += segmentWidth
            }
        }
    }

    private fun buildCode128Codes(value: String): List<Int> {
        val codes = mutableListOf<Int>()
        if (value.all { it in '0'..'9' } && value.length >= 2) {
            var index = 0
            if (value.length % 2 == 0) {
                codes += 105
            } else {
                codes += 104
                codes += value[0].code - 32
                if (value.length > 1) {
                    codes += 99
                }
                index = 1
            }
            while (index + 1 < value.length) {
                codes += value.substring(index, index + 2).toInt()
                index += 2
            }
        } else {
            codes += 104
            value.forEach { char ->
                codes += (char.code.coerceIn(32, 126) - 32)
            }
        }

        val checksum = (codes.first() + codes.drop(1).mapIndexed { index, code -> code * (index + 1) }.sum()) % 103
        codes += checksum
        codes += 106
        return codes
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
                        pendingHealthLatch?.countDown()
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

    private fun sdkConnectionState(sdk: CTPL): Boolean {
        return runCatching { sdk.isConnected }.getOrDefault(false)
    }

    private fun printerProblemMessage(status: Map<String, String>): String? {
        val cover = status.valueFor("DeviceCover")
        if (cover.equals("Open", ignoreCase = true)) return "Printer cover is open."

        val paused = status.valueFor("DevicePause")
        if (paused.equals("true", ignoreCase = true)) return "Printer is paused."

        val overheated = status.valueFor("DeviceOverHeat")
        if (overheated.equals("true", ignoreCase = true)) return "Printer is overheated."

        val paperProblem = status.entries.firstOrNull { (key, value) ->
            val lowerKey = key.lowercase(Locale.US)
            val lowerValue = value.lowercase(Locale.US)
            (lowerKey.contains("paper") || lowerValue.contains("paper") || value.contains("缺纸")) &&
                (lowerValue.contains("out") || lowerValue.contains("false") || value.contains("缺纸"))
        }
        if (paperProblem != null) return "Printer paper status is not ready: ${paperProblem.key}=${paperProblem.value}."

        return null
    }

    private fun statusSummary(status: Map<String, String>): String {
        return if (status.isEmpty()) {
            ""
        } else {
            status.entries.joinToString(", ") { "${it.key}=${it.value}" }
        }
    }

    private fun Map<String, String>.valueFor(key: String): String {
        return entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value.orEmpty()
    }

    private fun success(message: String): ChitengOfficialPrintResult {
        lastMessage = message
        lastError = ""
        return ChitengOfficialPrintResult.success(message)
    }

    private fun failure(message: String): ChitengOfficialPrintResult {
        lastMessage = ""
        lastError = message
        return ChitengOfficialPrintResult.failure(message)
    }

    private fun healthError(
        sdkAvailable: Boolean,
        sdkConnected: Boolean,
        message: String,
        onlineStatus: String = ONLINE_ERROR,
        rawError: String? = null,
    ): ChitengOfficialHealthResult {
        val error = if (rawError.isNullOrBlank()) message else "$message ${rawError.trim()}."
        lastMessage = ""
        lastError = error
        return ChitengOfficialHealthResult(
            sdkAvailable = sdkAvailable,
            sdkConnected = sdkConnected,
            onlineStatus = onlineStatus,
            message = "",
            error = error,
            rawStatus = emptyMap(),
        )
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

    data class StoreItemLabelPreviewPayload(
        val templateSize: String,
        val widthMm: Int,
        val heightMm: Int,
        val label: StoreItemLabelRow,
        val debugTemplate: String,
    ) {
        fun isCoordinateTest(): Boolean = debugTemplate == STORE_ITEM_PREVIEW_DEBUG_COORDINATE_TEST

        companion object {
            fun fromJson(raw: String): StoreItemLabelPreviewPayload {
                val json = JSONObject(raw)
                val printerProfile = json.optString("printer_profile").trim().uppercase(Locale.US)
                if (printerProfile != "CHITENG_S1_OFFICIAL") {
                    throw IllegalArgumentException("printer_profile must be CHITENG_S1_OFFICIAL.")
                }

                val printMode = json.optString("print_mode").trim().lowercase(Locale.US)
                if (printMode.isNotBlank() && printMode != STORE_ITEM_PREVIEW_PRINT_MODE) {
                    throw IllegalArgumentException("print_mode must be preview_one.")
                }

                val templateSize = json.optString("label_template_size").trim().lowercase(Locale.US)
                val widthMm: Int
                val heightMm: Int
                when (templateSize) {
                    STORE_ITEM_PREVIEW_TEMPLATE_60X40 -> {
                        widthMm = 60
                        heightMm = 40
                    }

                    STORE_ITEM_PREVIEW_TEMPLATE_40X30 -> {
                        widthMm = 40
                        heightMm = 30
                    }

                    else -> throw IllegalArgumentException("label_template_size must be 60x40 or 40x30.")
                }

                val debugTemplate = json.optString("debug_template").trim().lowercase(Locale.US)
                if (debugTemplate.isNotBlank() && debugTemplate != STORE_ITEM_PREVIEW_DEBUG_COORDINATE_TEST) {
                    throw IllegalArgumentException("debug_template must be coordinate_test when provided.")
                }
                if (debugTemplate == STORE_ITEM_PREVIEW_DEBUG_COORDINATE_TEST) {
                    if (printMode != STORE_ITEM_PREVIEW_PRINT_MODE) {
                        throw IllegalArgumentException("coordinate_test requires print_mode preview_one.")
                    }
                    if (templateSize != STORE_ITEM_PREVIEW_TEMPLATE_40X30) {
                        throw IllegalArgumentException("coordinate_test supports exactly one 40x30 label.")
                    }
                }

                val labelsJson = json.optJSONArray("labels")
                    ?: throw JSONException("labels is required.")
                if (labelsJson.length() != 1) {
                    throw IllegalArgumentException("Preview print only supports exactly one STORE_ITEM label.")
                }

                val labelJson = labelsJson.optJSONObject(0)
                    ?: throw JSONException("labels[0] must be an object.")
                val machineCode = labelJson.optString("machine_code").trim()
                if (!machineCode.matches(STORE_ITEM_MACHINE_CODE_PATTERN) || !machineCode.startsWith("5")) {
                    throw IllegalArgumentException("machine_code must be numeric and start with 5.")
                }

                val barcodeValue = labelJson.optString("barcode_value").trim()
                if (barcodeValue != machineCode) {
                    throw IllegalArgumentException("barcode_value must equal machine_code.")
                }

                if (!labelJson.has("price_kes")) {
                    throw IllegalArgumentException("price_kes is required.")
                }
                val priceKes = labelJson.optInt("price_kes", -1)
                if (priceKes < 0) {
                    throw IllegalArgumentException("price_kes must be greater than or equal to 0.")
                }

                val gradeValue = labelJson.optString("grade")
                    .ifBlank { labelJson.optString("pricing_type") }

                return StoreItemLabelPreviewPayload(
                    templateSize = templateSize,
                    widthMm = widthMm,
                    heightMm = heightMm,
                    label = StoreItemLabelRow(
                        machineCode = machineCode,
                        priceKes = priceKes,
                        categoryShort = cleanLabelText(labelJson.optString("category_short"), 24, "ITEM"),
                        grade = cleanLabelText(gradeValue, 8, "-"),
                    ),
                    debugTemplate = debugTemplate,
                )
            }

            private fun cleanLabelText(value: String, maxLength: Int, fallback: String): String {
                val cleaned = value
                    .trim()
                    .replace(Regex("\\s+"), " ")
                    .take(maxLength)
                return cleaned.ifBlank { fallback }
            }
        }
    }

    data class StoreItemLabelRow(
        val machineCode: String,
        val priceKes: Int,
        val categoryShort: String,
        val grade: String,
    )

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

    data class ChitengOfficialHealthResult(
        val sdkAvailable: Boolean,
        val sdkConnected: Boolean,
        val onlineStatus: String,
        val message: String,
        val error: String,
        val rawStatus: Map<String, String>,
    )

    companion object {
        private const val TEST_CODE = "TEST123456"
        private const val CONNECT_TIMEOUT_MS = 8000L
        private const val HEALTH_QUERY_TIMEOUT_MS = 1800L
        private const val UNKNOWN_CONNECT_CODE = -1
        private const val CONNECTED_BLE = 256
        private const val CONNECTED_SPP = 257
        private const val CONNECTED_USB = 258
        private const val PRINTER_NOT_RESPONDING_MESSAGE = "Printer is not responding. Turn on the printer and reconnect."
        const val ONLINE_ONLINE = "online"
        const val ONLINE_OFFLINE = "offline"
        const val ONLINE_UNKNOWN = "unknown"
        const val ONLINE_ERROR = "error"
    }
}
