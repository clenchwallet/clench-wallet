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
import net.clench.wallet.domain.model.HardwareWalletType

/**
 * Convert a base64 PSBT into BC-UR QR frames.
 * If the UR fits in a single QR code (~2.9KB PSBT), returns one static frame.
 * Otherwise returns animated fountain-coded frames.
 */
fun psbtToUrFrames(psbtBase64: String, maxFragmentLen: Int = 500): List<String> {
    val psbtBytes = Base64.decode(psbtBase64, Base64.DEFAULT)
    val cryptoPsbt = CryptoPSBT(psbtBytes)
    val ur = cryptoPsbt.toUR()

    // Single-frame threshold: QR alphanumeric mode caps at ~4296 chars,
    // but we use a conservative limit for reliable scanning.
    val singleFrameUr = ur.toString()
    if (singleFrameUr.length <= 2500) {
        return listOf(singleFrameUr.uppercase())
    }

    val encoder = UREncoder(ur, maxFragmentLen, 10, 0)
    val frames = mutableListOf<String>()
    val fragmentCount = encoder.seqLen
    repeat(maxOf(fragmentCount * 2, 1)) { frames.add(encoder.nextPart()) }
    return frames
}

/**
 * Encode a PSBT for QR display, using the appropriate format for the target device.
 * - Coldcard Q/Mk4: BBQr format (ZLIB compressed, Base32)
 * - All others: BC-UR (ur:crypto-psbt)
 */
fun encodePsbtForDevice(psbtBase64: String, deviceType: HardwareWalletType): List<String> {
    return when (deviceType) {
        HardwareWalletType.COLDCARD_Q,
        HardwareWalletType.COLDCARD_MK4 -> {
            val psbtBytes = Base64.decode(psbtBase64, Base64.DEFAULT)
            val frames = BBQrEncoder.encodePsbt(psbtBytes)
            android.util.Log.d("BBQr", "Encoded ${psbtBytes.size} bytes into ${frames.size} frames, first frame header: ${frames.firstOrNull()?.take(20)}, frame len: ${frames.firstOrNull()?.length}")
            frames
        }
        else -> psbtToUrFrames(psbtBase64)
    }
}

/**
 * Render a string as a QR bitmap.
 */
private fun encodeQrBitmap(content: String, size: Int = 512): Bitmap {
    val writer = QRCodeWriter()
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L
    )
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
 * Animated QR code composable for BC-UR or BBQr frames.
 * Cycles through frames at the given interval. For single frames, shows static QR.
 *
 * @param frameDelayMs Milliseconds between frames. 125ms (~8fps) for BC-UR,
 *                     600ms (~1.7fps) for BBQr (Coldcard Q scans slowly).
 */
@Composable
fun AnimatedQrCode(
    frames: List<String>,
    modifier: Modifier = Modifier,
    qrSize: Int = 512,
    frameDelayMs: Long = 125L
) {
    if (frames.isEmpty()) return

    var currentIndex by remember { mutableIntStateOf(0) }
    val isAnimated = frames.size > 1

    if (isAnimated) {
        LaunchedEffect(frames, frameDelayMs) {
            while (true) {
                delay(frameDelayMs)
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
