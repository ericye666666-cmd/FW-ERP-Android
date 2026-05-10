package com.directloop.pda

import org.json.JSONException
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.Locale

private const val UROVO_STORE_ITEM_PREVIEW_TEMPLATE_40X30 = "40x30"
private const val UROVO_STORE_ITEM_PREVIEW_PRINT_MODE = "preview_one"
private const val UROVO_PAGE_WIDTH_DOTS_40X30 = 320
private const val UROVO_PAGE_HEIGHT_DOTS_40X30 = 240
private const val UROVO_DEFAULT_FONT = ""
private const val UROVO_BARCODE_CODE128 = 20
private val UROVO_STORE_ITEM_MACHINE_CODE_PATTERN = Regex("^\\d{8,32}$")

class UrovoK300PrinterManagerClient {
    fun isAvailable(): Boolean {
        return runCatching { Class.forName("android.device.PrinterManager") }.isSuccess
    }

    fun getStatus(): UrovoK300PrintResult {
        val operations = mutableListOf<String>()
        return runWithPrinterManager(operations) { printer ->
            operations.add("open()")
            printer.open()
            operations.add("getStatus()")
            val statusCode = printer.getStatus()
            operations.add("close()")
            UrovoK300PrintResult(
                success = true,
                message = "Urovo K300 PrinterManager status read.",
                available = true,
                statusCode = statusCode,
                statusText = printer.statusText(statusCode),
                operations = operations.toList(),
            )
        }
    }

    fun printMinText(): UrovoK300PrintResult {
        val operations = mutableListOf<String>()
        return runWithPrinterManager(operations) { printer ->
            operations.add("open()")
            printer.open()
            operations.add("setupPage(320, 240)")
            printer.setupPage(320, 240)
            operations.add("clearPage()")
            printer.clearPage()
            operations.add("setSpeedLevel(2)")
            printer.setSpeedLevelIfAvailable(2)
            operations.add("setGrayLevel(3)")
            printer.setGrayLevelIfAvailable(3)
            operations.add("drawText(DIRECT LOOP,20,30,font,size=28,bold=true,italic=false,rotate=0)")
            printer.drawText("DIRECT LOOP", 20, 30, UROVO_DEFAULT_FONT, 28, true, false, 0)
            operations.add("drawText(K300 TEXT TEST,20,70,font,size=24,bold=false,italic=false,rotate=0)")
            printer.drawText("K300 TEXT TEST", 20, 70, UROVO_DEFAULT_FONT, 24, false, false, 0)
            operations.add("printPage(0)")
            val statusCode = printer.printPage(0)
            operations.add("close()")
            UrovoK300PrintResult(
                success = true,
                message = "Urovo K300 minimal text diagnostic was sent.",
                available = true,
                statusCode = statusCode,
                statusText = printer.statusText(statusCode),
                operations = operations.toList(),
            )
        }
    }

    fun printBlackBox(): UrovoK300PrintResult {
        val operations = mutableListOf<String>()
        return runWithPrinterManager(operations) { printer ->
            operations.add("open()")
            printer.open()
            operations.add("setupPage(320, 240)")
            printer.setupPage(320, 240)
            operations.add("clearPage()")
            printer.clearPage()
            operations.add("setSpeedLevel(2)")
            printer.setSpeedLevelIfAvailable(2)
            operations.add("setGrayLevel(3)")
            printer.setGrayLevelIfAvailable(3)
            operations.add("drawLine(20,20,280,20,4)")
            printer.drawLine(20, 20, 280, 20, 4)
            operations.add("drawLine(20,20,20,180,4)")
            printer.drawLine(20, 20, 20, 180, 4)
            operations.add("drawLine(20,180,280,180,4)")
            printer.drawLine(20, 180, 280, 180, 4)
            operations.add("drawLine(280,20,280,180,4)")
            printer.drawLine(280, 20, 280, 180, 4)
            operations.add("drawLine(40,60,260,160,30)")
            printer.drawLine(40, 60, 260, 160, 30)
            operations.add("printPage(0)")
            val statusCode = printer.printPage(0)
            operations.add("close()")
            UrovoK300PrintResult(
                success = true,
                message = "Urovo K300 black box diagnostic was sent.",
                available = true,
                statusCode = statusCode,
                statusText = printer.statusText(statusCode),
                operations = operations.toList(),
            )
        }
    }

    fun printStoreItemPreview(payload: StoreItemLabelPreviewPayload): UrovoK300PrintResult {
        val operations = payload.toPrinterManagerOperationSummary().toMutableList()
        return runWithPrinterManager(operations) { printer ->
            val label = payload.label
            printer.open()
            printer.setupPage(320, 240)
            printer.clearPage()
            printer.setSpeedLevelIfAvailable(2)
            printer.setGrayLevelIfAvailable(3)
            printer.drawText(label.shortHeaderText(), 20, 20, UROVO_DEFAULT_FONT, 24, true, false, 0)
            printer.drawText("KES ${label.priceKes}", 20, 55, UROVO_DEFAULT_FONT, 34, true, false, 0)
            printer.drawBarcode(label.machineCode, 20, 100, UROVO_BARCODE_CODE128, 2, 70, 0)
            printer.drawText(label.machineCode, 30, 185, UROVO_DEFAULT_FONT, 22, false, false, 0)
            val statusCode = printer.printPage(0)
            UrovoK300PrintResult(
                success = true,
                message = "Urovo K300 STORE_ITEM preview diagnostic was sent.",
                available = true,
                statusCode = statusCode,
                statusText = printer.statusText(statusCode),
                operations = operations.toList(),
            )
        }
    }

    private fun runWithPrinterManager(
        operations: MutableList<String>,
        block: (PrinterManagerSession) -> UrovoK300PrintResult,
    ): UrovoK300PrintResult {
        val printer = try {
            PrinterManagerSession()
        } catch (error: ClassNotFoundException) {
            return failure(
                message = "Urovo PrinterManager is not available on this device.",
                available = false,
                operations = operations,
            )
        } catch (error: ReflectiveOperationException) {
            return failure(
                message = "Urovo PrinterManager could not be initialized: ${error.message ?: "unknown error"}.",
                available = false,
                operations = operations,
            )
        } catch (error: RuntimeException) {
            return failure(
                message = "Urovo PrinterManager initialization failed: ${error.message ?: "unknown error"}.",
                available = false,
                operations = operations,
            )
        }

        return try {
            block(printer)
        } catch (error: ReflectiveOperationException) {
            failure(
                message = "Urovo PrinterManager call failed: ${error.message ?: "unknown error"}.",
                available = true,
                operations = operations,
            )
        } catch (error: RuntimeException) {
            failure(
                message = "Urovo PrinterManager call failed: ${error.message ?: "unknown error"}.",
                available = true,
                operations = operations,
            )
        } finally {
            runCatching {
                operations.add("close()")
                printer.close()
            }
        }
    }

    private fun failure(
        message: String,
        available: Boolean,
        operations: List<String>,
    ): UrovoK300PrintResult {
        return UrovoK300PrintResult(
            success = false,
            message = message,
            available = available,
            statusCode = null,
            statusText = if (available) "unknown" else "unavailable",
            operations = operations.toList(),
        )
    }

    data class StoreItemLabelPreviewPayload(
        val templateSize: String,
        val widthDots: Int,
        val heightDots: Int,
        val label: StoreItemLabelRow,
    ) {
        fun toPrinterManagerOperationSummary(): List<String> {
            return listOf(
                "open()",
                "setupPage($widthDots,$heightDots)",
                "clearPage()",
                "setSpeedLevel(2)",
                "setGrayLevel(3)",
                "drawText(category_short / grade)",
                "drawText(price_kes)",
                "drawBarcode(CODE_128,machine_code)",
                "drawText(machine_code)",
                "printPage(0)",
                "close()",
            )
        }

        companion object {
            fun fromJson(raw: String): StoreItemLabelPreviewPayload {
                val json = JSONObject(raw)
                val printerProfile = json.optString("printer_profile").trim().uppercase(Locale.US)
                if (printerProfile != "UROVO_K300") {
                    throw IllegalArgumentException("printer_profile must be UROVO_K300.")
                }

                val printMode = json.optString("print_mode").trim().lowercase(Locale.US)
                if (printMode.isNotBlank() && printMode != UROVO_STORE_ITEM_PREVIEW_PRINT_MODE) {
                    throw IllegalArgumentException("print_mode must be preview_one.")
                }

                val templateSize = json.optString("label_template_size").trim().lowercase(Locale.US)
                if (templateSize != UROVO_STORE_ITEM_PREVIEW_TEMPLATE_40X30) {
                    throw IllegalArgumentException("label_template_size must be 40x30 for UROVO_K300 diagnostics.")
                }

                val labelsJson = json.optJSONArray("labels")
                    ?: throw JSONException("labels is required.")
                if (labelsJson.length() != 1) {
                    throw IllegalArgumentException("Preview print only supports exactly one STORE_ITEM label.")
                }

                val labelJson = labelsJson.optJSONObject(0)
                    ?: throw JSONException("labels[0] must be an object.")
                val machineCode = labelJson.optString("machine_code").trim()
                if (!machineCode.matches(UROVO_STORE_ITEM_MACHINE_CODE_PATTERN) || !machineCode.startsWith("5")) {
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
                    widthDots = UROVO_PAGE_WIDTH_DOTS_40X30,
                    heightDots = UROVO_PAGE_HEIGHT_DOTS_40X30,
                    label = StoreItemLabelRow(
                        machineCode = machineCode,
                        priceKes = priceKes,
                        categoryShort = requiredCleanLabelText(
                            value = labelJson.optString("category_short"),
                            maxLength = 24,
                            errorMessage = "category_short is required.",
                        ),
                        grade = requiredCleanLabelText(
                            value = gradeValue,
                            maxLength = 8,
                            errorMessage = "grade or pricing_type is required.",
                        ),
                    ),
                )
            }

            private fun requiredCleanLabelText(value: String, maxLength: Int, errorMessage: String): String {
                val cleaned = value
                    .trim()
                    .replace(Regex("[\"\\r\\n]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .take(maxLength)
                if (cleaned.isBlank()) {
                    throw IllegalArgumentException(errorMessage)
                }
                return cleaned
            }
        }
    }

    data class StoreItemLabelRow(
        val machineCode: String,
        val priceKes: Int,
        val categoryShort: String,
        val grade: String,
    ) {
        fun shortHeaderText(): String {
            return listOf(categoryShort, grade)
                .filter { it.isNotBlank() }
                .joinToString(" / ")
                .take(30)
        }
    }

    data class UrovoK300PrintResult(
        val success: Boolean,
        val message: String,
        val available: Boolean,
        val statusCode: Int?,
        val statusText: String,
        val operations: List<String>,
    )

    private class PrinterManagerSession {
        private val printerClass = Class.forName("android.device.PrinterManager")
        private val printer = printerClass.getDeclaredConstructor().newInstance()
        private val intType = Int::class.javaPrimitiveType!!
        private val booleanType = Boolean::class.javaPrimitiveType!!
        private val statusCodes = StatusCodes.from(printerClass)

        fun open(): Int = callInt("open")

        fun close(): Int = callInt("close")

        fun setupPage(width: Int, height: Int): Int {
            return callInt("setupPage", arrayOf(intType, intType), width, height)
        }

        fun clearPage(): Int = callInt("clearPage")

        fun drawText(
            text: String,
            x: Int,
            y: Int,
            fontName: String,
            fontSize: Int,
            bold: Boolean,
            italic: Boolean,
            rotate: Int,
        ): Int {
            return callInt(
                "drawText",
                arrayOf(String::class.java, intType, intType, String::class.java, intType, booleanType, booleanType, intType),
                text,
                x,
                y,
                fontName,
                fontSize,
                bold,
                italic,
                rotate,
            )
        }

        fun drawBarcode(
            value: String,
            x: Int,
            y: Int,
            barcodeType: Int,
            width: Int,
            height: Int,
            rotate: Int,
        ): Int {
            return callInt(
                "drawBarcode",
                arrayOf(String::class.java, intType, intType, intType, intType, intType, intType),
                value,
                x,
                y,
                barcodeType,
                width,
                height,
                rotate,
            )
        }

        fun drawLine(
            x0: Int,
            y0: Int,
            x1: Int,
            y1: Int,
            width: Int,
        ): Int {
            return callInt(
                "drawLine",
                arrayOf(intType, intType, intType, intType, intType),
                x0,
                y0,
                x1,
                y1,
                width,
            )
        }

        fun getStatus(): Int = callInt("getStatus")

        fun printPage(mode: Int): Int {
            return callInt("printPage", arrayOf(intType), mode)
        }

        fun setSpeedLevelIfAvailable(level: Int): Int? {
            return optionalCallInt("setSpeedLevel", arrayOf(intType), level)
        }

        fun setGrayLevelIfAvailable(level: Int): Int? {
            return optionalCallInt("setGrayLevel", arrayOf(intType), level)
        }

        fun statusText(statusCode: Int?): String {
            return statusCodes.text(statusCode)
        }

        private fun callInt(
            methodName: String,
            parameterTypes: Array<Class<*>> = emptyArray(),
            vararg args: Any?,
        ): Int {
            val result = method(methodName, parameterTypes).invoke(printer, *args)
            return (result as? Number)?.toInt() ?: 0
        }

        private fun optionalCallInt(
            methodName: String,
            parameterTypes: Array<Class<*>>,
            vararg args: Any?,
        ): Int? {
            val method = runCatching { method(methodName, parameterTypes) }.getOrNull() ?: return null
            val result = method.invoke(printer, *args)
            return (result as? Number)?.toInt() ?: 0
        }

        private fun method(methodName: String, parameterTypes: Array<Class<*>>): Method {
            return printerClass.getMethod(methodName, *parameterTypes)
        }
    }

    private data class StatusCodes(
        val ok: Int,
        val outOfPaper: Int,
        val overHeat: Int,
        val underVoltage: Int,
        val busy: Int,
        val error: Int,
        val driverError: Int,
    ) {
        fun text(statusCode: Int?): String {
            return when (statusCode) {
                null -> "unknown"
                ok -> "ok"
                outOfPaper -> "out_of_paper"
                overHeat -> "over_heat"
                underVoltage -> "under_voltage"
                busy -> "busy"
                error -> "error"
                driverError -> "driver_error"
                else -> "unknown_$statusCode"
            }
        }

        companion object {
            fun from(printerClass: Class<*>): StatusCodes {
                return StatusCodes(
                    ok = statusConstant(printerClass, "PRNSTS_OK", 0),
                    outOfPaper = statusConstant(printerClass, "PRNSTS_OUT_OF_PAPER", 1),
                    overHeat = statusConstant(printerClass, "PRNSTS_OVER_HEAT", 2),
                    underVoltage = statusConstant(printerClass, "PRNSTS_UNDER_VOLTAGE", 3),
                    busy = statusConstant(printerClass, "PRNSTS_BUSY", 4),
                    error = statusConstant(printerClass, "PRNSTS_ERR", 5),
                    driverError = statusConstant(printerClass, "PRNSTS_ERR_DRIVER", 6),
                )
            }

            private fun statusConstant(printerClass: Class<*>, name: String, fallback: Int): Int {
                return runCatching {
                    val field = printerClass.getField(name)
                    field.getInt(null)
                }.getOrElse {
                    runCatching {
                        val field = printerClass.getDeclaredField(name)
                        field.isAccessible = true
                        field.getInt(null)
                    }.getOrDefault(fallback)
                }
            }
        }
    }
}
