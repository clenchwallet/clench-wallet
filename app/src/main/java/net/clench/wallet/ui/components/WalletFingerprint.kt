package net.clench.wallet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Deterministic identicon fingerprint.
 * Uses 5×5 symmetric grid (left half mirrored to right) + foreground color from hash bytes.
 * Similar to GitHub's identicon approach.
 *
 * @param fingerprintBytes first 8 bytes of SHA-256(masterFingerprint [+ passphrase]) — drives the identicon pattern/color
 * @param masterFingerprint the raw 4-byte BIP32 master key fingerprint for display text (e.g. "a1b2c3d4")
 */
@Composable
fun WalletFingerprint(
    fingerprintBytes: ByteArray,
    masterFingerprint: ByteArray? = null,
    size: Dp = 80.dp,
    label: String = "Wallet fingerprint — verify this matches when restoring",
    modifier: Modifier = Modifier
) {
    if (fingerprintBytes.size < 8) return

    // Derive foreground color from bytes 0-2 (hue from byte 0)
    val hue = (fingerprintBytes[0].toInt() and 0xFF) * 360f / 256f
    val saturation = 0.6f + (fingerprintBytes[1].toInt() and 0xFF) / 256f * 0.3f  // 0.6–0.9
    val lightness = 0.35f + (fingerprintBytes[2].toInt() and 0xFF) / 256f * 0.2f  // 0.35–0.55
    val fgColor = Color.hsl(hue, saturation, lightness)
    val bgColor = Color.hsl(hue, 0.1f, 0.95f)  // near-white tinted background

    // 5×5 grid, left 3 columns defined by bits, col 4 = mirror of col 2, col 5 = mirror of col 1
    // Use bytes 3-7 (5 bytes = 5 rows × 3 bits each from high bits)
    val grid = Array(5) { row ->
        val byte = fingerprintBytes[3 + row].toInt() and 0xFF
        val col0 = (byte shr 7) and 1  // bit 7
        val col1 = (byte shr 6) and 1  // bit 6
        val col2 = (byte shr 5) and 1  // bit 5
        // Mirror: col3 = col1, col4 = col0
        intArrayOf(col0, col1, col2, col1, col0)
    }

    // Standard Bitcoin master key fingerprint: 8 lowercase hex chars, no separators
    // e.g. "a1b2c3d4" — matches Sparrow, Bitcoin Core, all BIP32 tools
    val hexText = remember(masterFingerprint?.toList(), fingerprintBytes.toList()) {
        val source = masterFingerprint ?: fingerprintBytes
        source.take(4).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        Canvas(modifier = Modifier.size(size)) {
            val cellSize = this.size.width / 5f
            // Background
            drawRect(bgColor, size = this.size)
            // Cells
            for (row in 0..4) {
                for (col in 0..4) {
                    if (grid[row][col] == 1) {
                        drawRect(
                            color = fgColor,
                            topLeft = Offset(col * cellSize, row * cellSize),
                            size = Size(cellSize, cellSize)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            hexText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }
}
