package net.clench.wallet.data.network

import java.io.IOException
import java.io.Reader

/** Bounds line allocation and cumulative input before JSON parsing. Reader may buffer ahead. */
internal class BoundedLineReader(
    private val reader: Reader,
    private val maxLineChars: Int,
    private val maxTotalChars: Long
) {
    private var consumed = 0L
    init { require(maxLineChars > 0 && maxTotalChars > 0) }

    fun readLine(): String? {
        val line = StringBuilder(minOf(maxLineChars, 4096))
        while (true) {
            val next = reader.read()
            if (next == -1) return if (line.isEmpty()) null else line.toString().removeSuffix("\r")
            if (++consumed > maxTotalChars) throw IOException("Response exceeds total input limit")
            if (next == '\n'.code) return line.toString().removeSuffix("\r")
            if (line.length == maxLineChars) throw IOException("Response line exceeds input limit")
            line.append(next.toChar())
        }
    }
}
