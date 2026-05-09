package com.directloop.pda

import java.nio.charset.StandardCharsets

enum class BluetoothPrinterTestProtocol {
    TSPL,
    TSPL_SIMPLE_TEXT,
    TSPL_DENSITY_TEXT,
    TSPL_NO_GAP_CONTINUOUS,
    TSPL_GAP_DETECT,
    RAW_LF_FEED,
    CPCL,
    CPCL_SIMPLE_TEXT,
    ESC_POS,
    ESC_POS_TEXT,
    CHITENG_S1_OFFICIAL,
    ;

    companion object {
        val supportedNames: String = entries.joinToString(", ") { it.name }

        fun from(input: String?): BluetoothPrinterTestProtocol? {
            val normalized = input
                ?.trim()
                ?.uppercase()
                ?.replace("-", "_")
                ?.replace("/", "_")
                ?.replace(" ", "_")
                ?: return null
            return entries.firstOrNull { it.name == normalized }
        }
    }
}

object BluetoothPrinterTestPayloads {
    private const val TEST_CODE = "TEST123456"

    fun build(
        protocol: BluetoothPrinterTestProtocol,
        selectedProfile: String,
        timestamp: String,
    ): ByteArray {
        return when (protocol) {
            BluetoothPrinterTestProtocol.TSPL -> buildTspl(selectedProfile, timestamp)
            BluetoothPrinterTestProtocol.TSPL_SIMPLE_TEXT -> buildTsplSimpleText()
            BluetoothPrinterTestProtocol.TSPL_DENSITY_TEXT -> buildTsplDensityText()
            BluetoothPrinterTestProtocol.TSPL_NO_GAP_CONTINUOUS -> buildTsplNoGapContinuous()
            BluetoothPrinterTestProtocol.TSPL_GAP_DETECT -> buildTsplGapDetect()
            BluetoothPrinterTestProtocol.RAW_LF_FEED -> buildRawLfFeed()
            BluetoothPrinterTestProtocol.CPCL -> buildCpcl(selectedProfile, timestamp)
            BluetoothPrinterTestProtocol.CPCL_SIMPLE_TEXT -> buildCpclSimpleText()
            BluetoothPrinterTestProtocol.ESC_POS -> buildEscPos(selectedProfile, timestamp)
            BluetoothPrinterTestProtocol.ESC_POS_TEXT -> buildEscPosText()
            BluetoothPrinterTestProtocol.CHITENG_S1_OFFICIAL -> error("CHITENG_S1_OFFICIAL is printed through the official CTPL SDK client.")
        }
    }

    private fun tsplPayload(value: String): ByteArray {
        return value
            .trimIndent()
            .replace("\n", "\r\n")
            .plus("\r\n")
            .toByteArray(StandardCharsets.US_ASCII)
    }

    private fun buildTspl(selectedProfile: String, timestamp: String): ByteArray {
        return tsplPayload(
            """
SIZE 60 mm,40 mm
GAP 2 mm,0 mm
DIRECTION 1
CLS
TEXT 30,30,"3",0,1,1,"DIRECT LOOP"
TEXT 30,65,"3",0,1,1,"PRINTER TEST"
TEXT 30,105,"2",0,1,1,"MODEL: $selectedProfile"
TEXT 30,135,"2",0,1,1,"$TEST_CODE"
TEXT 30,165,"2",0,1,1,"$timestamp"
BARCODE 30,205,"128",60,1,0,2,2,"$TEST_CODE"
PRINT 1,1
""",
        )
    }

    private fun buildTsplSimpleText(): ByteArray {
        return tsplPayload(
            """
SIZE 60 mm,40 mm
GAP 2 mm,0 mm
CLS
TEXT 20,20,"0",0,2,2,"DIRECT LOOP"
TEXT 20,70,"0",0,1,1,"S1 TEST"
PRINT 1,1
""",
        )
    }

    private fun buildTsplDensityText(): ByteArray {
        return tsplPayload(
            """
SIZE 60 mm,40 mm
GAP 2 mm,0 mm
SPEED 2
DENSITY 12
DIRECTION 1
REFERENCE 0,0
SET TEAR ON
CLS
TEXT 20,20,"0",0,2,2,"DIRECT LOOP"
TEXT 20,70,"0",0,1,1,"S1 TEST"
PRINT 1,1
""",
        )
    }

    private fun buildTsplNoGapContinuous(): ByteArray {
        return tsplPayload(
            """
SIZE 60 mm,40 mm
CLS
TEXT 20,20,"0",0,2,2,"DIRECT LOOP"
TEXT 20,70,"0",0,1,1,"S1 TEST"
PRINT 1,1
""",
        )
    }

    private fun buildTsplGapDetect(): ByteArray {
        return tsplPayload(
            """
GAPDETECT
SIZE 60 mm,40 mm
GAP 2 mm,0 mm
CLS
TEXT 20,20,"0",0,2,2,"DIRECT LOOP"
TEXT 20,70,"0",0,1,1,"S1 TEST"
PRINT 1,1
""",
        )
    }

    private fun buildRawLfFeed(): ByteArray {
        return "\n\n\n\n".toByteArray(StandardCharsets.US_ASCII)
    }

    private fun buildCpcl(selectedProfile: String, timestamp: String): ByteArray {
        return """
! 0 200 200 320 1
TEXT 4 0 30 25 DIRECT LOOP
TEXT 4 0 30 60 PRINTER TEST
TEXT 0 0 30 100 MODEL: $selectedProfile
TEXT 0 0 30 130 $TEST_CODE
TEXT 0 0 30 160 $timestamp
BARCODE 128 1 1 55 30 200 $TEST_CODE
FORM
PRINT
""".trimIndent()
            .replace("\n", "\r\n")
            .plus("\r\n")
            .toByteArray(StandardCharsets.US_ASCII)
    }

    private fun buildCpclSimpleText(): ByteArray {
        return """
! 0 200 200 240 1
TEXT 0 0 20 20 DIRECT LOOP
TEXT 0 0 20 55 S1 TEST
FORM
PRINT
""".trimIndent()
            .replace("\n", "\r\n")
            .plus("\r\n")
            .toByteArray(StandardCharsets.US_ASCII)
    }

    private fun buildEscPos(selectedProfile: String, timestamp: String): ByteArray {
        return (
            "\u001B@" +
                "DIRECT LOOP\n" +
                "PRINTER TEST\n" +
                "MODEL: $selectedProfile\n" +
                "$TEST_CODE\n" +
                "$timestamp\n\n\n"
            ).toByteArray(StandardCharsets.US_ASCII)
    }

    private fun buildEscPosText(): ByteArray {
        return (
            "\u001B@" +
                "DIRECT LOOP\n" +
                "S1 TEST\n\n\n"
            ).toByteArray(StandardCharsets.US_ASCII)
    }
}
