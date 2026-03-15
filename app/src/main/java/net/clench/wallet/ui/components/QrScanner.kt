package net.clench.wallet.ui.components

import android.Manifest
import android.util.Base64
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
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import java.util.concurrent.Executors

/**
 * QR Scanner composable that handles both static QR and BC-UR animated QR (PSBT).
 * Requires CAMERA permission granted before showing.
 */
@Composable
fun QrScanner(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var isProcessing by remember { mutableStateOf(false) }

    // Check camera permission
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) onCancel()
    }

    LaunchedEffect(Unit) {
        val result = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            hasPermission = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
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
                setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
            }}

            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setTargetResolution(Size(1280, 720))
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

                                if (text.lowercase().startsWith("ur:")) {
                                    // BC-UR animated frame
                                    urDecoder.receivePart(text)
                                    progress = urDecoder.estimatedPercentComplete.toFloat()

                                    val decoderResult = urDecoder.result
                                    if (decoderResult != null && decoderResult.type == ResultType.SUCCESS) {
                                        isProcessing = true
                                        val ur = decoderResult.ur
                                        val cryptoPsbt = ur.decodeFromRegistry() as CryptoPSBT
                                        val psbtBase64 = Base64.encodeToString(cryptoPsbt.psbt, Base64.NO_WRAP)
                                        onResult(psbtBase64)
                                    }
                                } else {
                                    // Static QR — could be base64 PSBT or other data
                                    isProcessing = true
                                    onResult(text)
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
                        } catch (_: Exception) { }
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
                "Scanning animated QR: ${(progress * 100).toInt()}%",
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
