package com.morpheus.family.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder

/** Pairing payload prefix so the scanner can recognize our codes. */
const val QR_PREFIX = "MORPHEUS:"

/** Renders [text] as a QR code image, or nothing if encoding fails. */
@Composable
fun QrCode(text: String, sizePx: Int = 512, modifier: Modifier = Modifier) {
    val bitmap = remember(text, sizePx) {
        runCatching {
            BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, sizePx, sizePx)
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "QR de pareamento", modifier = modifier)
    }
}
