package net.clench.wallet.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.sparrowwallet.hummingbird.ResultType
import com.sparrowwallet.hummingbird.URDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Decodes a SeedQR (Standard format) string into a BIP39 mnemonic phrase.
 *
 * Standard SeedQR format (as produced by Sparrow, SeedSigner, etc.):
 *   Each BIP39 word is represented as its zero-padded 4-digit index in the
 *   English wordlist (0000–2047), concatenated into a single digit string.
 *   12-word seed → 48-digit string
 *   24-word seed → 96-digit string
 *
 * This function is ONLY responsible for index → word mapping (i.e. decoding
 * the QR's compact numeric encoding into human-readable words). It performs
 * no cryptographic validation.
 *
 * The resulting mnemonic string is always passed to BDK's Mnemonic.fromString()
 * before any key derivation occurs. BDK owns all BIP39 cryptographic validation
 * (wordlist membership, checksum verification, entropy extraction).
 *
 * The bundled bip39_english.txt is the canonical BIP39 English wordlist
 * (2048 words, identical to the list embedded in BDK's native libbdkffi.so).
 * It is used solely as a lookup table here — no security property depends on it.
 */
fun decodeSeedQr(context: Context, raw: String): String? {
    val wordlist = loadBip39Wordlist(context) ?: return null
    return decodeStandardSeedQr(raw, wordlist)
}

fun decodeSeedQr(context: Context, raw: String, byteSegments: List<ByteArray>): String? {
    val wordlist = loadBip39Wordlist(context) ?: return null
    decodeStandardSeedQr(raw, wordlist)?.let { return it }
    for (segment in byteSegments) {
        decodeCompactSeedQr(segment, wordlist)?.let { return it }
    }
    return null
}

internal fun decodeStandardSeedQr(raw: String, wordlist: List<String>): String? {
    val digits = raw.trim()
    if (!digits.all { it.isDigit() }) return null
    if (digits.length != 48 && digits.length != 96) return null
    if (wordlist.size != 2048) return null

    val words = mutableListOf<String>()
    var i = 0
    while (i < digits.length) {
        val index = digits.substring(i, i + 4).toIntOrNull() ?: return null
        if (index < 0 || index >= 2048) return null
        words.add(wordlist[index])
        i += 4
    }
    // Return the mnemonic phrase — caller must validate via BDK Mnemonic.fromString()
    return words.joinToString(" ")
}

internal fun decodeCompactSeedQr(payload: ByteArray, wordlist: List<String>): String? {
    if (payload.size != 16 && payload.size != 32) return null
    if (wordlist.size != 2048) return null

    val entropyBits = payload.size * 8
    val checksumBits = entropyBits / 32
    val totalBits = entropyBits + checksumBits
    val digest = MessageDigest.getInstance("SHA-256").digest(payload)
    val words = mutableListOf<String>()

    for (wordIndex in 0 until totalBits / 11) {
        var index = 0
        for (bitOffset in 0 until 11) {
            val absoluteBit = wordIndex * 11 + bitOffset
            val bit = if (absoluteBit < entropyBits) {
                bitAt(payload, absoluteBit)
            } else {
                bitAt(digest, absoluteBit - entropyBits)
            }
            index = (index shl 1) or bit
        }
        words.add(wordlist[index])
    }
    return words.joinToString(" ")
}

private fun bitAt(bytes: ByteArray, bitIndex: Int): Int {
    val byte = bytes[bitIndex / 8].toInt() and 0xff
    val shift = 7 - (bitIndex % 8)
    return (byte ushr shift) and 1
}

/**
 * Encodes a BIP39 mnemonic into Standard SeedQR numeric format.
 */
fun encodeSeedQr(context: Context, words: List<String>): String? {
    if (words.size != 12 && words.size != 24) return null
    val wordlist = loadBip39Wordlist(context) ?: return null
    val indexByWord = wordlist.withIndex().associate { it.value to it.index }
    val encoded = StringBuilder(words.size * 4)
    for (word in words) {
        val index = indexByWord[word.lowercase()] ?: return null
        encoded.append(index.toString().padStart(4, '0'))
    }
    return encoded.toString()
}

private fun loadBip39Wordlist(context: Context): List<String>? {
    return try {
        context.assets.open("bip39_english.txt").bufferedReader().readLines()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .takeIf { it.size == 2048 }
    } catch (_: Exception) {
        null
    }
}

/**
 * Check whether the device has any camera available.
 * Uses PackageManager feature check AND CameraManager enumeration
 * to avoid false positives on emulators/devices that report the feature
 * but have no actual camera hardware.
 */
fun hasCameraAvailable(context: Context): Boolean {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
        return false
    }
    // Double-check with CameraManager — getCameraIdList() works without CAMERA permission
    return try {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        cameraManager?.cameraIdList?.isNotEmpty() == true
    } catch (_: Exception) {
        false
    }
}

/**
 * QR Scanner composable that handles static QR plus animated BC-UR, BBQr,
 * and p1ofN text QR sequences for hardware-wallet imports/signing.
 * Requires CAMERA permission granted before showing.
 *
 * @param onError optional callback invoked when the camera fails to initialize
 *                (e.g. no camera hardware). The scanner auto-cancels after calling this.
 */
@Composable
fun QrScanner(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
    onError: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var isProcessing by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    // BBQr accumulator for Coldcard Q animated frames
    val bbqrFrames = remember { mutableMapOf<Int, String>() }
    var bbqrTotalFrames by remember { mutableIntStateOf(0) }
    var bbqrEncoding by remember { mutableStateOf(' ') }
    var bbqrFileType by remember { mutableStateOf(' ') }

    // Generic p1ofN animated-text accumulator used by SeedSigner/Specter-style exports
    val multipartTextFrames = remember { mutableMapOf<Int, String>() }
    var multipartTextTotalFrames by remember { mutableIntStateOf(0) }

    // Early camera availability check
    LaunchedEffect(Unit) {
        if (!hasCameraAvailable(context)) {
            val msg = "Camera not available. Paste your xpub or descriptor below instead."
            cameraError = msg
            onError?.invoke(msg)
            onCancel()
            return@LaunchedEffect
        }
    }

    // Check camera permission
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) onCancel()
    }

    LaunchedEffect(Unit) {
        if (!hasCameraAvailable(context)) return@LaunchedEffect // skip if no camera
        val result = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        if (result == PackageManager.PERMISSION_GRANTED) {
            hasPermission = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Show error state if camera failed
    if (cameraError != null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(cameraError!!, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = onCancel) { Text("OK") }
            }
        }
        return
    }

    if (!hasPermission) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera permission required")
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            val urDecoder = remember { URDecoder() }
            val executor = remember { Executors.newSingleThreadExecutor() }
            val multiReader = remember { MultiFormatReader().apply {
                setHints(
                    mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        DecodeHintType.TRY_HARDER to true,
                        DecodeHintType.CHARACTER_SET to "UTF-8"
                    )
                )
            }}

            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = try {
                            cameraProviderFuture.get()
                        } catch (e: Exception) {
                            val msg = "Camera initialization failed. Paste your xpub or descriptor below instead."
                            cameraError = msg
                            onError?.invoke(msg)
                            onCancel()
                            return@addListener
                        }

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setTargetResolution(Size(1920, 1080))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        imageAnalysis.setAnalyzer(executor) { imageProxy ->
                            if (isProcessing) {
                                imageProxy.close()
                                return@setAnalyzer
                            }

                            val buffer = imageProxy.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)

                            val source = PlanarYUVLuminanceSource(
                                bytes,
                                imageProxy.width, imageProxy.height,
                                0, 0,
                                imageProxy.width, imageProxy.height,
                                false
                            )
                            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

                            try {
                                val result = multiReader.decodeWithState(binaryBitmap)
                                val text = result.text
                                val trimmedText = text.trim()
                                val lowerText = trimmedText.lowercase(Locale.US)

                                val multipartTextMatch = Regex("^p(\\d+)of(\\d+)\\s+(.+)$", RegexOption.IGNORE_CASE).find(trimmedText)

                                if (BBQrEncoder.isBBQr(text)) {
                                    // BBQr animated frames are used by Coldcard Q for both signing
                                    // payloads and wallet exports. P=PSBT and T=final transaction are
                                    // binary signing payloads; other file types such as J=Generic JSON
                                    // are textual wallet-export payloads for onboarding.
                                    val frame = BBQrEncoder.parseBBQrFrame(text)
                                    if (frame != null) {
                                        if (bbqrTotalFrames != frame.totalFrames ||
                                            bbqrEncoding != frame.encoding ||
                                            bbqrFileType != frame.fileType
                                        ) {
                                            bbqrFrames.clear()
                                            bbqrTotalFrames = frame.totalFrames
                                            bbqrEncoding = frame.encoding
                                            bbqrFileType = frame.fileType
                                        }
                                        bbqrFrames[frame.frameIndex] = frame.data
                                        progress = bbqrFrames.size.toFloat() / frame.totalFrames.toFloat()

                                        if (bbqrFrames.size == frame.totalFrames) {
                                            val zeroBasedIndexes = 0 until frame.totalFrames
                                            val oneBasedIndexes = 1..frame.totalFrames
                                            val orderedChunks = when {
                                                zeroBasedIndexes.all { bbqrFrames.containsKey(it) } -> zeroBasedIndexes.map { i -> bbqrFrames[i].orEmpty() }
                                                oneBasedIndexes.all { bbqrFrames.containsKey(it) } -> oneBasedIndexes.map { i -> bbqrFrames[i].orEmpty() }
                                                else -> null
                                            }

                                            if (orderedChunks != null) {
                                                isProcessing = true
                                                try {
                                                    val rawBytes = BBQrEncoder.reassemble(orderedChunks, bbqrEncoding)
                                                    onResult(HardwareWalletQrPayloadDecoder.decodeBbqrPayload(bbqrFileType, rawBytes))
                                                } catch (_: Exception) {
                                                    // Reset on decode error and keep scanning
                                                    bbqrFrames.clear()
                                                    bbqrTotalFrames = 0
                                                    bbqrEncoding = ' '
                                                    bbqrFileType = ' '
                                                    isProcessing = false
                                                }
                                            }
                                        }
                                    }
                                } else if (multipartTextMatch != null) {
                                    val index = multipartTextMatch.groupValues[1].toIntOrNull()
                                    val total = multipartTextMatch.groupValues[2].toIntOrNull()
                                    val data = multipartTextMatch.groupValues[3]
                                    if (index != null && total != null && index in 1..total) {
                                        if (multipartTextTotalFrames != total) {
                                            multipartTextFrames.clear()
                                            multipartTextTotalFrames = total
                                        }
                                        multipartTextFrames[index] = data
                                        progress = multipartTextFrames.size.toFloat() / total.toFloat()
                                        if (multipartTextFrames.size == total) {
                                            isProcessing = true
                                            onResult((1..total).joinToString("") { i -> multipartTextFrames[i].orEmpty() }.trim())
                                        }
                                    }
                                } else if (lowerText.startsWith("ur:")) {
                                    // BC-UR animated frame
                                    urDecoder.receivePart(lowerText)
                                    progress = urDecoder.estimatedPercentComplete.toFloat()

                                    val decoderResult = urDecoder.result
                                    if (decoderResult != null && decoderResult.type == ResultType.SUCCESS) {
                                        val ur = decoderResult.ur
                                        val decodedPayload = HardwareWalletQrPayloadDecoder.decodeUrPayload(ur)
                                        if (!decodedPayload.isNullOrBlank()) {
                                            isProcessing = true
                                            onResult(decodedPayload)
                                        }
                                    }
                                } else {
                                    // Static QR — check for SeedQR (Standard format) first,
                                    // then fall through as raw text (xpub, descriptor, base64 PSBT, etc.)
                                    isProcessing = true
                                    val decoded = decodeSeedQr(context, text, result.byteSegments())
                                    onResult(decoded ?: text)
                                }
                            } catch (_: NotFoundException) {
                                // No QR code found in this frame
                            } catch (_: Exception) {
                                // Ignore decode errors
                            } finally {
                                multiReader.reset()
                            }

                            imageProxy.close()
                        }

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis
                            )
                        } catch (e: Exception) {
                            // Camera binding failed — no usable camera on this device
                            val msg = "Camera not available. Paste your xpub or descriptor below instead."
                            cameraError = msg
                            onError?.invoke(msg)
                            onCancel()
                        }
                    }, androidx.core.content.ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Progress bar for animated QR
        if (progress > 0f && progress < 1f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )
            Text(
                "Scanning animated QR: ${(progress * 100).toInt()}%${when {
                    bbqrTotalFrames > 0 -> " (${bbqrFrames.size}/$bbqrTotalFrames)"
                    multipartTextTotalFrames > 0 -> " (${multipartTextFrames.size}/$multipartTextTotalFrames)"
                    else -> ""
                }}",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) { Text("Cancel") }
    }
}

private fun Result.byteSegments(): List<ByteArray> {
    val raw = resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? Iterable<*> ?: return emptyList()
    return raw.mapNotNull { it as? ByteArray }
}
