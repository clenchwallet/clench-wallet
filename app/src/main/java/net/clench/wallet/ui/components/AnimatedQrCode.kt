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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.UREncoder
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import kotlinx.coroutines.delay
import net.clench.wallet.domain.model.HardwareWalletType

private const val SEEDSIGNER_PSBT_UR_FRAGMENT_LEN = 120
private const val PASSPORT_PSBT_UR_FRAGMENT_LEN = 240

/**
 * Convert a base64 PSBT into BC-UR QR frames.
 * If allowed and the UR fits in a single QR code (~2.9KB PSBT), returns one static frame.
 * Otherwise returns animated fountain-coded frames.
 */
fun psbtToUrFrames(
    psbtBase64: String,
    maxFragmentLen: Int = 500,
    allowSingleFrame: Boolean = true
): List<String> {
    val psbtBytes = Base64.decode(psbtBase64, Base64.DEFAULT)
    return psbtBytesToUrFrames(psbtBytes, maxFragmentLen, allowSingleFrame)
}

internal fun psbtBytesToUrFrames(
    psbtBytes: ByteArray,
    maxFragmentLen: Int = 500,
    allowSingleFrame: Boolean = true
): List<String> {
    val cryptoPsbt = CryptoPSBT(psbtBytes)
    val ur = cryptoPsbt.toUR()

    // Single-frame threshold: QR alphanumeric mode caps at ~4296 chars,
    // but we use a conservative limit for reliable scanning.
    val singleFrameUr = ur.toString().uppercase()
    if (allowSingleFrame && singleFrameUr.length <= 2500) {
        return listOf(singleFrameUr)
    }

    val encoder = UREncoder(ur, maxFragmentLen, 10, 0)
    val frames = mutableListOf<String>()
    val fragmentCount = encoder.seqLen
    repeat(maxOf(fragmentCount * 2, 1)) { frames.add(encoder.nextPart().uppercase()) }
    return frames
}

/**
 * Encode a PSBT for QR display, using the appropriate format for the target device.
 * - Coldcard Q: BBQr format (Base32/Hex per Coinkite BBQr)
 * - SeedSigner: low-density animated BC-UR (ur:crypto-psbt)
 * - Passport: medium-density animated BC-UR (ur:crypto-psbt)
 * - All others: BC-UR (ur:crypto-psbt)
 */
fun encodePsbtForDevice(psbtBase64: String, deviceType: HardwareWalletType): List<String> {
    return when {
        deviceType == HardwareWalletType.COLDCARD_Q -> {
            val psbtBytes = Base64.decode(psbtBase64, Base64.DEFAULT)
            // Coldcard Q's scanner is more reliable with lower-density BBQr frames.
            // Smaller chunks create more frames, but each QR is easier for the Q to lock onto.
            val frames = BBQrEncoder.encodePsbt(psbtBytes, maxChunkChars = 480)
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BBQr", "Encoded ${psbtBytes.size} bytes into ${frames.size} frames, first frame header: ${frames.firstOrNull()?.take(20)}, frame len: ${frames.firstOrNull()?.length}")
            frames
        }
        deviceType.requiresAnimatedPsbtUr() -> {
            // SeedSigner itself uses 120-byte UR2 fragments for high-density PSBT QR.
            // Passport can handle denser frames, but still avoids dense static PSBT QRs.
            psbtToUrFrames(
                psbtBase64,
                maxFragmentLen = deviceType.psbtUrFragmentLen(),
                allowSingleFrame = false
            )
        }
        else -> psbtToUrFrames(psbtBase64)
    }
}

internal fun HardwareWalletType.requiresAnimatedPsbtUr(): Boolean {
    return this == HardwareWalletType.SEEDSIGNER ||
        this == HardwareWalletType.FOUNDATION_PASSPORT
}

internal fun HardwareWalletType.psbtUrFragmentLen(): Int {
    return when (this) {
        HardwareWalletType.SEEDSIGNER -> SEEDSIGNER_PSBT_UR_FRAGMENT_LEN
        HardwareWalletType.FOUNDATION_PASSPORT -> PASSPORT_PSBT_UR_FRAGMENT_LEN
        else -> 500
    }
}

internal fun HardwareWalletType.psbtQrFrameDelayMs(): Long {
    return when (this) {
        HardwareWalletType.COLDCARD_Q -> 250L
        HardwareWalletType.COLDCARD_MK4,
        HardwareWalletType.COLDCARD_MK5 -> 1000L
        HardwareWalletType.FOUNDATION_PASSPORT -> 250L
        else -> 125L
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
 *                     250ms (~4fps) for BBQr (Coldcard Q recommendation).
 * @param qrSizeDp Display size of the QR code. Default 360.dp (increased from 280.dp
 *                  for better scanning at typical hardware wallet reading distances).
 * @param autoAdvance Whether to auto-advance frames.
 * @param forcedFrameIndex When non-null, display this frame instead of the internal timer index.
 */
@Composable
fun AnimatedQrCode(
    frames: List<String>,
    modifier: Modifier = Modifier,
    qrSize: Int = 512,
    qrSizeDp: Dp = 360.dp,
    frameDelayMs: Long = 125L,
    autoAdvance: Boolean = true,
    forcedFrameIndex: Int? = null
) {
    if (frames.isEmpty()) return

    var currentIndex by remember { mutableIntStateOf(0) }
    val isAnimated = frames.size > 1
    val displayIndex = forcedFrameIndex?.floorMod(frames.size) ?: currentIndex

    if (isAnimated && autoAdvance && forcedFrameIndex == null) {
        LaunchedEffect(frames, frameDelayMs) {
            while (true) {
                delay(frameDelayMs)
                currentIndex = (currentIndex + 1) % frames.size
            }
        }
    }

    val bitmap = remember(frames, displayIndex) {
        encodeQrBitmap(frames[displayIndex], qrSize)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR Code",
            modifier = Modifier.size(qrSizeDp)
        )
        if (isAnimated) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "${displayIndex + 1} / ${frames.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
