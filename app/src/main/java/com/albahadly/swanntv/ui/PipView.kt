package com.albahadly.swanntv.ui

import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.albahadly.swanntv.model.Camera
import com.albahadly.swanntv.playback.StreamPlayer

/** The picture-in-picture window: one camera on its light grid stream, and its name. */
@Composable
fun PipView(state: ViewerState, camera: Camera) {
    var view by remember { mutableStateOf<SurfaceView?>(null) }
    var player by remember { mutableStateOf<StreamPlayer?>(null) }

    DisposableEffect(view, camera.index) {
        val v = view
        if (v != null) player = state.manager.startPip(camera, v)
        onDispose {
            if (v != null) {
                state.manager.stopPip()
                player = null
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { context -> SurfaceView(context).also { view = it } }, modifier = Modifier.fillMaxSize())
        if (player?.status != StreamPlayer.Status.LIVE) {
            state.manager.lastPictures[camera.index]?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(
            camera.name,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.TextBright,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .background(Palette.Scrim, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
