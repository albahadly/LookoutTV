package com.albahadly.swanntv.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.albahadly.swanntv.ui.Palette
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Shows the QR code that opens the phone setup page. */
@Composable
fun PhoneSetupPane(url: String?, address: String?, code: String, idle: Boolean, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Set up from your phone", "Typing camera addresses is much easier on a phone")
        if (url == null && idle) {
            Hint("Phone setup stopped after 15 minutes without use, to keep the TV private.")
            TvButton("Start again", primary = true, onClick = onRetry)
            return
        }
        if (url == null) {
            Hint("Phone setup isn't available: the TV has no home-network address, or the port is busy.", Palette.Error)
            TvButton("Try again", primary = true, onClick = onRetry)
            return
        }
        val qr = remember(url) { qrBitmap(url) }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                // Nearest-neighbour scaling keeps the modules crisp for phone cameras.
                Image(qr, contentDescription = "QR code for phone setup", filterQuality = FilterQuality.None,
                    modifier = Modifier.size(180.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Step(1, "Connect your phone to the same home Wi-Fi as the TV.")
                Step(2, "Point the phone's camera at the code and open the link.")
                Step(3, "Add, edit, test or reorder cameras on the phone. The TV updates straight away.")
                Hint("No camera app? Type this address in the phone's browser:")
                Text(address ?: url.substringBefore("/?"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextBright)
                if (code.isNotEmpty()) {
                    Hint("then enter this code:")
                    Text(code.chunked(3).joinToString(" "), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextBright)
                }
                Hint("The page works while Settings is open on the TV. Leaving Settings ends it, and a new code is made next time.")
            }
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(20.dp).background(Palette.Border, RoundedCornerShape(10.dp)),
        ) {
            Text("$number", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        }
        Text(text, fontSize = 11.sp, color = Palette.Text, modifier = Modifier.padding(top = 2.dp))
    }
}

/** QR code as a 1-pixel-per-module bitmap (scaled up crisply on screen). */
private fun qrBitmap(text: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
