package com.albahadly.lookouttv.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.util.Date

/** Current time in the TV's 12/24-hour format, refreshed every few seconds. */
@Composable
fun rememberClock(): String {
    val context = LocalContext.current
    val format = remember(context) { DateFormat.getTimeFormat(context) }
    val time by produceState(format.format(Date())) {
        while (true) {
            value = format.format(Date())
            delay(5_000)
        }
    }
    return time
}

/** True for the remote's OK button. */
fun Key.isSelect(): Boolean =
    this == Key.DirectionCenter || this == Key.Enter || this == Key.NumPadEnter

/** A focusable pill button for the header (layout picker). */
@Composable
fun FocusChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val background = when {
        focused -> Palette.TextBright
        selected -> Palette.Border
        else -> Palette.Surface
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp && it.key.isSelect()) {
                    onClick(); true
                } else false
            }
            .focusable()
            .background(background, RoundedCornerShape(8.dp))
            .defaultMinSize(minWidth = 44.dp, minHeight = 30.dp)
            .padding(horizontal = 12.dp),
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (focused) Palette.OnAccent else if (selected) Palette.TextBright else Palette.Quiet,
        )
    }
}

/** "[OK] Full screen" style hint for the footer. */
@Composable
fun KeyHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .background(Palette.Tile, RoundedCornerShape(4.dp))
                .border(1.dp, Palette.Border, RoundedCornerShape(4.dp))
                .defaultMinSize(minWidth = 22.dp, minHeight = 20.dp)
                .padding(horizontal = 6.dp),
        ) {
            Text(key, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
        }
        Text(label, fontSize = 10.sp, color = Palette.Quiet)
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .background(Palette.Scrim, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Box(Modifier.size(5.dp).background(Palette.Live, CircleShape))
        Text("LIVE", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Palette.TextBright, letterSpacing = 0.6.sp)
    }
}

@Composable
fun HSpace(width: Int) = Spacer(Modifier.size(width.dp, 1.dp))
