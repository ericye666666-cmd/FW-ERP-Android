package com.directloop.pda

import java.nio.charset.StandardCharsets

enum class BluetoothPrinterTestProtocol {
    TSPL,
    CPCL,
    ESC_POS,
    ;

    companion object {
        fun from(input: String?): BluetoothPrinterTestProtocol? {
            val normalized = input
                ?.trim()
                ?.uppercase()
                ?.replace("-", "_")
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
            BluetoothPrinterTestProtocol.CPCL -> buildCpcl(selectedProfile, timestamp)
            BluetoothPrinterTestProtocol.ESC_POS -> buildEscPos(selectedProfile, timestamp)
        }
    }

    private fun buildTspl(selectedProfile: String, timestamp: String): ByteArray {
        return """
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
""".trimIndent()
            .replace("\n", "\r\n")
            .plus("\r\n")
            .toByteArray(StandardCharsets.US_ASCII)
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
}
