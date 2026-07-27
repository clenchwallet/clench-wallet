package net.clench.wallet.verification

import co.nstant.`in`.cbor.CborBuilder
import co.nstant.`in`.cbor.CborDecoder
import co.nstant.`in`.cbor.CborEncoder
import co.nstant.`in`.cbor.model.Map
import co.nstant.`in`.cbor.model.UnicodeString
import java.io.ByteArrayOutputStream
import net.clench.wallet.ui.components.CoinkiteTapCardKind

/**
 * Deterministic APDU/CBOR simulator for parser and interrupted-tap tests.
 * It does not claim to emulate NFC timing, RF behavior, or card cryptography.
 */
internal class CoinkiteTapCardSimulator(
    private val kind: CoinkiteTapCardKind
) {
    private var interruptNext = false

    fun interruptNextResponse() {
        interruptNext = true
    }

    fun command(commandName: String): ByteArray {
        val body = encodeBody(CborBuilder().addMap().put("cmd", commandName).end().build())
        require(body.size <= 255) { "Simulator command is too large for a short APDU" }
        return byteArrayOf(
            0x00,
            0xcb.toByte(),
            0x00,
            0x00,
            body.size.toByte()
        ) + body
    }

    fun transceive(command: ByteArray): ByteArray {
        if (interruptNext) {
            interruptNext = false
            return byteArrayOf(0x90.toByte())
        }
        require(command.size >= 5) { "APDU is truncated" }
        if (command[1].toInt() and 0xff == 0xa4) return success(ByteArray(0))
        val dataLength = command[4].toInt() and 0xff
        require(command.size == 5 + dataLength) { "APDU length is inconsistent" }
        val commandMap = CborDecoder.decode(command.copyOfRange(5, command.size)).single() as Map
        val commandName = (commandMap[UnicodeString("cmd")] as? UnicodeString)?.string
            ?: return error(400, "missing cmd")

        return when (commandName) {
            "status" -> status()
            "read" -> read()
            "wait" -> mapResponse { it.put("success", true).put("auth_delay", 0L) }
            "certs" -> certs()
            "check" -> mapResponse {
                it.put("auth_sig", ByteArray(64) { index -> (index + 1).toByte() })
                    .put("card_nonce", nonce(3))
            }
            "xpub" -> if (kind == CoinkiteTapCardKind.TAPSIGNER) {
                mapResponse {
                    it.put("xpub", ByteArray(78) { index -> index.toByte() }.also { key -> key[45] = 0x02 })
                        .put("card_nonce", nonce(4))
                }
            } else {
                error(406, "unsupported")
            }
            "new" -> mapResponse {
                it.put("slot", 0L)
                    .put("card_nonce", nonce(5))
            }
            "derive" -> mapResponse {
                it.put("sig", ByteArray(64) { index -> (index + 6).toByte() })
                    .put("chain_code", ByteArray(32) { index -> (index + 7).toByte() })
                    .put("master_pubkey", compressedKey(8))
                    .put("pubkey", compressedKey(9))
                    .put("card_nonce", nonce(10))
            }
            "backup" -> mapResponse {
                it.put("data", ByteArray(96) { index -> (index + 11).toByte() })
                    .put("card_nonce", nonce(12))
            }
            "dump" -> mapResponse {
                it.put("slot", 0L)
                    .put("used", true)
                    .put("sealed", true)
                    .put("addr", "tb1qsimulated000000000000000000000000000000")
                    .put("pubkey", compressedKey(5))
                    .put("card_nonce", nonce(5))
            }
            else -> error(404, "unsupported command")
        }
    }

    private fun status(): ByteArray {
        val map = CborBuilder().addMap()
            .put("proto", 1L)
            .put("ver", "sim-1")
            .put("birth", 1L)
            .put("tapsigner", kind == CoinkiteTapCardKind.TAPSIGNER)
            .put("testnet", true)
            .put("tampered", false)
            .put("pubkey", compressedKey(1))
            .put("card_nonce", nonce(1))
        if (kind == CoinkiteTapCardKind.TAPSIGNER) {
            map.put("num_backups", 1L)
            val path = map.putArray("path")
            listOf(0x80000054L, 0x80000001L, 0x80000000L).forEach(path::add)
            return encode(path.end().end().build())
        }
        map.put("addr", "tb1qsimulated000000000000000000000000000000")
        val slots = map.putArray("slots")
        slots.add(0L).add(10L)
        return encode(slots.end().end().build())
    }

    private fun read(): ByteArray {
        if (kind != CoinkiteTapCardKind.SATSCARD) return error(406, "unsupported")
        return mapResponse {
            it.put("sig", ByteArray(64) { index -> (index + 7).toByte() })
                .put("pubkey", compressedKey(2))
                .put("card_nonce", nonce(2))
        }
    }

    private fun certs(): ByteArray {
        val map = CborBuilder().addMap()
        val chain = map.putArray("cert_chain")
        chain.add(ByteArray(65) { index -> (index + 9).toByte() })
        return encode(chain.end().end().build())
    }

    private fun mapResponse(
        builder: (co.nstant.`in`.cbor.builder.MapBuilder<CborBuilder>) ->
            co.nstant.`in`.cbor.builder.MapBuilder<CborBuilder>
    ): ByteArray {
        val map = CborBuilder().addMap()
        return encode(builder(map).end().build())
    }

    private fun error(code: Long, message: String): ByteArray =
        mapResponse { it.put("code", code).put("error", message) }

    private fun encode(items: List<co.nstant.`in`.cbor.model.DataItem>): ByteArray {
        return success(encodeBody(items))
    }

    private fun encodeBody(items: List<co.nstant.`in`.cbor.model.DataItem>): ByteArray {
        val output = ByteArrayOutputStream()
        CborEncoder(output).encode(items)
        return output.toByteArray()
    }

    private fun success(body: ByteArray): ByteArray =
        body + byteArrayOf(0x90.toByte(), 0x00)

    private fun nonce(offset: Int): ByteArray =
        ByteArray(16) { index -> (index + offset).toByte() }

    private fun compressedKey(offset: Int): ByteArray =
        ByteArray(33) { index -> if (index == 0) 0x02 else (index + offset).toByte() }
}
