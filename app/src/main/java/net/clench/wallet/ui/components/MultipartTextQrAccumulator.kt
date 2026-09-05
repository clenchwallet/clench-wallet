package net.clench.wallet.ui.components

/** Legacy pNofM has no stream digest: reject detectable conflicts; do not claim stream authentication. */
internal class MultipartTextQrAccumulator(private val maxFrames: Int, private val maxChars: Int) {
    private val frames = mutableMapOf<Int, String>()
    private var length = 0
    var totalFrames = 0
        private set
    val collectedFrames: Int get() = frames.size

    fun reset() { frames.clear(); length = 0; totalFrames = 0 }

    fun receive(index: Int, total: Int, data: String): String? {
        try {
            require(total in 1..maxFrames && index in 1..total) { "Invalid animated QR frame count" }
            require(data.isNotEmpty() && data.length <= maxChars) { "Invalid animated QR frame size" }
            require(totalFrames == 0 || totalFrames == total) { "Animated QR stream changed" }
            val previous = frames[index]
            require(previous == null || previous == data) { "Conflicting animated QR frame" }
            if (previous == null) {
                require(data.length <= maxChars - length) { "Animated QR exceeds import safety limit" }
                frames[index] = data
                length += data.length
            }
            totalFrames = total
            return if (frames.size == total) (1..total).joinToString("") { frames.getValue(it) }.trim() else null
        } catch (e: IllegalArgumentException) {
            reset()
            throw e
        }
    }
}
