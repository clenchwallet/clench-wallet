package net.clench.wallet.security

import java.io.InputStream
import java.io.Reader

object InputLimits {
    const val SECRET_TEXT_CHARS = 64 * 1024
    const val LABEL_TEXT_CHARS = 2 * 1024 * 1024
    const val RAW_TRANSACTION_CHARS = 2 * 1024 * 1024
    const val PSBT_BYTES = 4 * 1024 * 1024
}

fun Reader.readTextBounded(maxChars: Int): String {
    require(maxChars > 0)
    val output = StringBuilder(minOf(maxChars, 8_192))
    val buffer = CharArray(8_192)
    try {
        while (true) {
            val read = read(buffer)
            if (read < 0) return output.toString()
            check(output.length + read <= maxChars) { "Imported text exceeds the ${maxChars}-character safety limit" }
            output.append(buffer, 0, read)
        }
    } finally {
        buffer.fill('\u0000')
    }
}

fun InputStream.readBytesBounded(maxBytes: Int): ByteArray {
    require(maxBytes > 0)
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 8_192))
    val buffer = ByteArray(8_192)
    var total = 0
    try {
        while (true) {
            val read = read(buffer)
            if (read < 0) return output.toByteArray()
            total += read
            check(total <= maxBytes) { "Imported binary data exceeds the ${maxBytes}-byte safety limit" }
            output.write(buffer, 0, read)
        }
    } finally {
        buffer.fill(0)
    }
}
