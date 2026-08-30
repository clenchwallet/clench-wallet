package net.clench.wallet.ui.components

import co.nstant.`in`.cbor.CborBuilder
import co.nstant.`in`.cbor.CborEncoder
import java.io.ByteArrayOutputStream
import net.clench.wallet.verification.CoinkiteTapCardSimulator
import net.clench.wallet.verification.VerificationPropertyHarness
import net.clench.wallet.verification.VerificationPropertyHarness.bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoinkiteProtocolHostileTest {
    @Test
    fun `TAPSIGNER simulator exercises APDU status wait and xpub responses`() {
        val simulator = CoinkiteTapCardSimulator(CoinkiteTapCardKind.TAPSIGNER)
        val status = TapsignerTapProtocol.parseStatusResponse(
            simulator.transceive(TapsignerTapProtocol.statusCommand())
        )
        val wait = TapsignerTapProtocol.parseWaitResponse(
            simulator.transceive(TapsignerTapProtocol.waitCommand())
        )
        val xpub = TapsignerTapProtocol.parseTapsignerXpubResponse(
            simulator.transceive(simulator.command("xpub"))
        )
        val initialized = TapsignerTapProtocol.parseTapsignerNewResponse(
            simulator.transceive(simulator.command("new"))
        )
        val derived = TapsignerTapProtocol.parseTapsignerDeriveResponse(
            simulator.transceive(simulator.command("derive"))
        )
        val backup = TapsignerTapProtocol.parseTapsignerBackupResponse(
            simulator.transceive(simulator.command("backup"))
        )

        assertEquals(CoinkiteTapCardKind.TAPSIGNER, status.kind)
        assertEquals("m/84'/1'/0'", status.displayPath)
        assertEquals(0L, wait.authDelaySeconds)
        assertEquals(78, xpub.xpub.size)
        assertEquals(0L, initialized.slot)
        assertEquals(32, derived.chainCode.size)
        assertEquals(96, backup.data.size)
    }

    @Test
    fun `SATSCARD simulator exercises status read cert and dump responses`() {
        val simulator = CoinkiteTapCardSimulator(CoinkiteTapCardKind.SATSCARD)
        val status = TapsignerTapProtocol.parseStatusResponse(
            simulator.transceive(TapsignerTapProtocol.statusCommand())
        )
        val read = TapsignerTapProtocol.parseReadResponse(
            simulator.transceive(TapsignerTapProtocol.readCommand(ByteArray(16)))
        )
        val certs = TapsignerTapProtocol.parseCertsResponse(
            simulator.transceive(TapsignerTapProtocol.certsCommand())
        )
        val dump = TapsignerTapProtocol.parseDumpResponse(
            simulator.transceive(TapsignerTapProtocol.dumpCommand(0))
        )

        assertEquals(CoinkiteTapCardKind.SATSCARD, status.kind)
        assertEquals(64, read.signature.size)
        assertEquals(1, certs.certChain.size)
        assertEquals(0L, dump.slot)
        assertTrue(dump.sealed == true)
    }

    @Test
    fun `interrupted NFC response fails closed and a fresh retry succeeds`() {
        val simulator = CoinkiteTapCardSimulator(CoinkiteTapCardKind.TAPSIGNER)
        simulator.interruptNextResponse()

        assertThrows(IllegalStateException::class.java) {
            TapsignerTapProtocol.parseStatusResponse(
                simulator.transceive(TapsignerTapProtocol.statusCommand())
            )
        }

        val retried = TapsignerTapProtocol.parseStatusResponse(
            simulator.transceive(TapsignerTapProtocol.statusCommand())
        )
        assertEquals(CoinkiteTapCardKind.TAPSIGNER, retried.kind)
    }

    @Test
    fun `multiple CBOR roots and oversized NFC responses are rejected`() {
        val first = encodedMap("ver", "one")
        val second = encodedMap("ver", "two")
        val multiple = first + second + byteArrayOf(0x90.toByte(), 0x00)
        val oversized = ByteArray(TapsignerTapProtocol.MAX_RESPONSE_BYTES + 1).also {
            it[it.lastIndex - 1] = 0x90.toByte()
            it[it.lastIndex] = 0x00
        }

        assertThrows(IllegalStateException::class.java) {
            TapsignerTapProtocol.parseStatusResponse(multiple)
        }
        assertThrows(IllegalStateException::class.java) {
            TapsignerTapProtocol.parseStatusResponse(oversized)
        }
    }

    @Test
    fun `duplicate CBOR keys and wrong typed fields are rejected`() {
        val duplicateVersion = byteArrayOf(
            0xA2.toByte(),
            0x63, 0x76, 0x65, 0x72,
            0x63, 0x6F, 0x6E, 0x65,
            0x63, 0x76, 0x65, 0x72,
            0x63, 0x74, 0x77, 0x6F,
            0x90.toByte(), 0x00
        )
        val wrongTypedPath = CborBuilder().addMap()
            .put("path", "not-an-array")
            .end()
            .build()

        assertThrows(Exception::class.java) {
            TapsignerTapProtocol.parseStatusResponse(duplicateVersion)
        }
        assertThrows(IllegalStateException::class.java) {
            TapsignerTapProtocol.parseStatusResponse(successful(wrongTypedPath))
        }
    }

    @Test
    fun `recursive and malformed indefinite CBOR are rejected before decoding`() {
        val deeplyNested = ByteArray(20) { 0x81.toByte() } +
            byteArrayOf(0xA0.toByte(), 0x90.toByte(), 0x00)
        val unterminatedMap = byteArrayOf(
            0xBF.toByte(),
            0x63, 0x76, 0x65, 0x72,
            0x63, 0x6F, 0x6E, 0x65,
            0x90.toByte(), 0x00
        )
        val mapEndingAfterKey = byteArrayOf(
            0xBF.toByte(),
            0x63, 0x76, 0x65, 0x72,
            0xFF.toByte(),
            0x90.toByte(), 0x00
        )
        val textWithByteStringChunk = byteArrayOf(
            0xBF.toByte(),
            0x63, 0x76, 0x65, 0x72,
            0x7F, 0x41, 0x31, 0xFF.toByte(),
            0xFF.toByte(),
            0x90.toByte(), 0x00
        )
        val bareBreak = byteArrayOf(0xFF.toByte(), 0x90.toByte(), 0x00)

        assertThrows(IllegalStateException::class.java) {
            TapsignerTapProtocol.parseStatusResponse(deeplyNested)
        }
        assertThrows(IllegalStateException::class.java) {
            TapsignerTapProtocol.parseStatusResponse(unterminatedMap)
        }
        assertThrows(IllegalStateException::class.java) {
            TapsignerTapProtocol.parseStatusResponse(mapEndingAfterKey)
        }
        assertThrows(IllegalStateException::class.java) {
            TapsignerTapProtocol.parseStatusResponse(textWithByteStringChunk)
        }
        assertThrows(IllegalStateException::class.java) {
            TapsignerTapProtocol.parseStatusResponse(bareBreak)
        }
    }

    @Test
    fun `outbound APDU parameters are bounded before encoding`() {
        assertThrows(IllegalArgumentException::class.java) {
            TapsignerTapProtocol.readCommand(ByteArray(15))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TapsignerTapProtocol.checkCommand(ByteArray(17))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TapsignerTapProtocol.dumpCommand(10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TapsignerTapProtocol.authenticatedNewSatscardCommand(
                slot = -1,
                cardPubkey = ByteArray(33),
                cardNonce = ByteArray(16),
                cvc = "123456".toCharArray(),
                chainCode = ByteArray(32)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TapsignerTapProtocol.authenticatedDeriveCommand(
                path = listOf(-1),
                nonce = ByteArray(16),
                cardPubkey = ByteArray(33),
                cardNonce = ByteArray(16),
                cvc = "123456".toCharArray()
            )
        }
    }

    @Test
    fun `well formed CBOR with hostile semantic bounds is rejected`() {
        val statusMap = CborBuilder().addMap()
        val path = statusMap.putArray("path")
        repeat(9) { path.add(it.toLong()) }
        val excessivePath = successful(path.end().end().build())

        val certsMap = CborBuilder().addMap()
        val chain = certsMap.putArray("cert_chain")
        repeat(9) { chain.add(ByteArray(65) { it.toByte() }) }
        val excessiveChain = successful(chain.end().end().build())

        val dump = CborBuilder().addMap()
            .put("slot", 99L)
            .put("pubkey", ByteArray(32))
            .put("card_nonce", ByteArray(15))
            .end()
            .build()
        val conflictingKind = CborBuilder().addMap()
            .put("tapsigner", true)
            .put("pubkey", ByteArray(33))
            .put("card_nonce", ByteArray(16))
            .put("addr", "tb1qconflicting")
            .putArray("slots")
            .add(0L)
            .add(10L)
            .end()
            .end()
            .build()

        assertThrows(IllegalStateException::class.java) {
            TapsignerTapProtocol.parseStatusResponse(excessivePath)
        }
        assertThrows(IllegalStateException::class.java) {
            TapsignerTapProtocol.parseCertsResponse(excessiveChain)
        }
        assertThrows(IllegalStateException::class.java) {
            TapsignerTapProtocol.parseDumpResponse(successful(dump))
        }
        assertThrows(IllegalStateException::class.java) {
            TapsignerTapProtocol.parseStatusResponse(successful(conflictingKind))
        }
    }

    @Test
    fun `hostile APDU response corpus never causes VM-level failures`() {
        val parsers: List<(ByteArray) -> Unit> = listOf(
            { TapsignerTapProtocol.parseStatusResponse(it) },
            { TapsignerTapProtocol.parseReadResponse(it) },
            { TapsignerTapProtocol.parseCertsResponse(it) },
            { TapsignerTapProtocol.parseCheckResponse(it) },
            { TapsignerTapProtocol.parseWaitResponse(it) },
            { TapsignerTapProtocol.parseTapsignerXpubResponse(it) },
            { TapsignerTapProtocol.parseTapsignerNewResponse(it) },
            { TapsignerTapProtocol.parseTapsignerDeriveResponse(it) },
            { TapsignerTapProtocol.parseTapsignerBackupResponse(it) },
            { TapsignerTapProtocol.parseDumpResponse(it) }
        )

        VerificationPropertyHarness.forAll(seed = 0x4E464346555A5AL) { random, _ ->
            val response = random.bytes(random.nextInt(4_096))
            parsers.forEach { parser ->
                VerificationPropertyHarness.assertNoFatalParserFailure {
                    parser(response)
                }
            }
        }
    }

    private fun encodedMap(key: String, value: String): ByteArray {
        val output = ByteArrayOutputStream()
        CborEncoder(output).encode(CborBuilder().addMap().put(key, value).end().build())
        return output.toByteArray()
    }

    private fun successful(items: List<co.nstant.`in`.cbor.model.DataItem>): ByteArray {
        val output = ByteArrayOutputStream()
        CborEncoder(output).encode(items)
        return output.toByteArray() + byteArrayOf(0x90.toByte(), 0x00)
    }
}
