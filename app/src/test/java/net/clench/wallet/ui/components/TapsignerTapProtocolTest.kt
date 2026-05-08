package net.clench.wallet.ui.components

import co.nstant.`in`.cbor.CborBuilder
import co.nstant.`in`.cbor.CborEncoder
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapsignerTapProtocolTest {

    @Test
    fun `select command uses Coinkite applet id`() {
        assertEquals(
            "00a404000ff0436f696e6b697465434152447631",
            TapsignerTapProtocol.selectAppletCommand().toHex()
        )
    }

    @Test
    fun `status command is a short Tap Protocol APDU`() {
        val command = TapsignerTapProtocol.statusCommand()

        assertEquals("00cb0000", command.take(4).toByteArray().toHex())
        assertEquals(command.size - 5, command[4].toInt() and 0xFF)
    }

    @Test
    fun `status parser extracts Tapsigner metadata`() {
        val response = tapsignerStatusResponse()

        val status = TapsignerTapProtocol.parseStatusResponse(response)

        assertTrue(status.isTapsigner)
        assertFalse(status.isSatscard)
        assertEquals(CoinkiteTapCardKind.TAPSIGNER, status.kind)
        assertEquals("1.1.0", status.version)
        assertEquals(700553L, status.birthHeight)
        assertEquals("m/84'/0'/0'", status.displayPath)
        assertEquals(3L, status.numberOfBackups)
        assertEquals("Tapsigner detected: firmware 1.1.0, path m/84'/0'/0', 3 backups", status.summary())
        assertEquals(66, status.cardPubkeyHex?.length)
        assertEquals(32, status.cardNonceHex?.length)
    }

    @Test
    fun `status parser extracts Satscard metadata`() {
        val response = satscardStatusResponse()

        val status = TapsignerTapProtocol.parseStatusResponse(response)

        assertFalse(status.isTapsigner)
        assertTrue(status.isSatscard)
        assertEquals(CoinkiteTapCardKind.SATSCARD, status.kind)
        assertEquals("1.2.0", status.version)
        assertEquals(725000L, status.birthHeight)
        assertEquals("bc1qexampleaddress000000000000000000000000000", status.address)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), status.slots)
        assertEquals(false, status.isTestnet)
        assertEquals(false, status.isTampered)
        assertEquals(
            "SATSCARD detected: firmware 1.2.0, address bc1qexampleaddress000000000000000000000000000, 5 slots",
            status.summary()
        )
    }

    @Test
    fun `status parser rejects failed APDU status word`() {
        val failure = byteArrayOf(0x6D, 0x00)

        assertFalse(TapsignerTapProtocol.isSuccessResponse(failure))
        try {
            TapsignerTapProtocol.parseStatusResponse(failure)
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("0x6D00"))
            return
        }
        error("Expected failed status word to be rejected")
    }

    private fun tapsignerStatusResponse(): ByteArray {
        val path = listOf(0x80000054L, 0x80000000L, 0x80000000L)
        val map = CborBuilder().addMap()
            .put("proto", 1L)
            .put("ver", "1.1.0")
            .put("birth", 700553L)
            .put("tapsigner", true)
            .put("num_backups", 3L)
            .put("pubkey", ByteArray(33) { index -> if (index == 0) 0x02.toByte() else index.toByte() })
            .put("card_nonce", ByteArray(16) { index -> (index + 1).toByte() })
        val pathArray = map.putArray("path")
        path.forEach { pathArray.add(it) }
        val out = ByteArrayOutputStream()
        CborEncoder(out).encode(pathArray.end().end().build())
        return out.toByteArray() + byteArrayOf(0x90.toByte(), 0x00)
    }

    private fun satscardStatusResponse(): ByteArray {
        val map = CborBuilder().addMap()
            .put("proto", 1L)
            .put("ver", "1.2.0")
            .put("birth", 725000L)
            .put("tapsigner", false)
            .put("addr", "bc1qexampleaddress000000000000000000000000000")
            .put("testnet", false)
            .put("tampered", false)
            .put("pubkey", ByteArray(33) { index -> if (index == 0) 0x02.toByte() else (index + 1).toByte() })
            .put("card_nonce", ByteArray(16) { index -> (index + 2).toByte() })
        val slotsArray = map.putArray("slots")
        listOf(0L, 1L, 2L, 3L, 4L).forEach { slotsArray.add(it) }
        val out = ByteArrayOutputStream()
        CborEncoder(out).encode(slotsArray.end().end().build())
        return out.toByteArray() + byteArrayOf(0x90.toByte(), 0x00)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
