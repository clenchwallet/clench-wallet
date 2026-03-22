package net.clench.wallet.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.UREncoder
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import kotlinx.coroutines.delay

/**
 * Convert a base64 PSBT into BC-UR animated QR frames.
 */
fun psbtToUrFrames(psbtBase64: String, maxFragmentLen: Int = 200): List<String> {
    val psbtBytes = Base64.decode(psbtBase64, Base64.DEFAULT)
    val cryptoPsbt = CryptoPSBT(psbtBytes)
    val ur = cryptoPsbt.toUR()
    val encoder = UREncoder(ur, maxFragmentLen, 10, 0)
    val frames = mutableListOf<String>()
    val fragmentCount = encoder.seqLen
    repeat(maxOf(fragmentCount * 2, 1)) { frames.add(encoder.nextPart()) }
    return frames
}

/**
 * Render a string as a QR bitmap.
 */
private fun encodeQrBitmap(content: String, size: Int = 512): Bitmap {
    val writer = QRCodeWriter()
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    return bitmap
}

/**
 * Animated QR code composable for BC-UR frames.
 * Cycles through frames at ~8fps. For single frames, shows static QR.
 */
@Composable
fun AnimatedQrCode(
    frames: List<String>,
    modifier: Modifier = Modifier,
    qrSize: Int = 512
) {
    if (frames.isEmpty()) return

    var currentIndex by remember { mutableIntStateOf(0) }
    val isAnimated = frames.size > 1

    if (isAnimated) {
        LaunchedEffect(frames) {
            while (true) {
                delay(125L) // ~8fps
                currentIndex = (currentIndex + 1) % frames.size
            }
        }
    }

    val bitmap = remember(frames, currentIndex) {
        encodeQrBitmap(frames[currentIndex], qrSize)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR Code",
            modifier = Modifier.size(280.dp)
        )
        if (isAnimated) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "${currentIndex + 1} / ${frames.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
