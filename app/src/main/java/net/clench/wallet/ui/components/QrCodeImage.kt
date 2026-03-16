package net.clench.wallet.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder

@Composable
fun QrCodeImage(
    data: String,
    modifier: Modifier = Modifier,
    size: Int = 512
) {
    val bitmap = remember(data) {
        try {
            val encoder = BarcodeEncoder()
            encoder.encodeBitmap(data, BarcodeFormat.QR_CODE, size, size).asImageBitmap()
        } catch (e: Exception) { null }
    }

    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "QR Code",
            modifier = modifier
                .background(Color.White)
                .padding(8.dp)
        )
    }
}
