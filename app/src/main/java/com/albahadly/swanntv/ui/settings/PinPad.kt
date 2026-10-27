package com.albahadly.swanntv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.albahadly.swanntv.ui.Palette

/**
 * Four-digit PIN entry that works with any remote: an on-screen keypad for remotes without
 * number keys, and the number keys directly when the remote has them.
 *
 * [confirm] = true asks for the PIN twice (setting a new PIN). [onPin] returns false to reject
 * the PIN (wrong PIN), which clears it and shows [wrongMessage].
 */
@Composable
fun PinPad(
    title: String,
    subtitle: String,
    confirm: Boolean = false,
    wrongMessage: String = "Wrong PIN, try again",
    /** Non-null while entry is locked after too many wrong PINs. */
    lockMessage: () -> String? = { null },
    onCancel: () -> Unit,
    onPin: (String) -> Boolean,
) {
    var digits by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val keypadFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { keypadFocus.requestFocus() }
    }
    // While locked, the "wait N s" text counts down by itself instead of waiting for a key.
    LaunchedEffect(message) {
        if (lockMessage() == null) return@LaunchedEffect
        while (true) {
            delay(1_000)
            val lock = lockMessage()
            message = lock ?: break
        }
        message = null
    }

    fun complete(pin: String) {
        when {
            confirm && first == null -> {
                first = pin
                message = null
            }
            confirm && first != pin -> {
                first = null
                message = "The PINs didn't match, start again"
            }
            !onPin(pin) -> message = lockMessage() ?: wrongMessage
        }
        digits = ""
    }

    fun type(d: Char) {
        lockMessage()?.let {
            message = it
            return
        }
        if (digits.length >= 4) return
        digits += d
        if (digits.length == 4) complete(digits)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = digitKey(event.key)
                when {
                    digit != null -> { type(digit); true }
                    event.key == Key.Backspace || event.key == Key.Delete -> { digits = digits.dropLast(1); true }
                    else -> false
                }
            },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Text(
                if (confirm && first != null) "Enter the same PIN again" else subtitle,
                fontSize = 11.sp,
                color = Palette.Quiet,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier
                            .size(14.dp)
                            .background(if (i < digits.length) Palette.Accent else Palette.Border, CircleShape)
                    )
                }
            }
            Text(message ?: " ", fontSize = 11.sp, color = Palette.Error)

            val rows = listOf("123", "456", "789")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                rows.forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEachIndexed { c, d ->
                            val mod = if (r == 0 && c == 0) Modifier.width(56.dp).focusRequester(keypadFocus) else Modifier.width(56.dp)
                            TvButton(d.toString(), modifier = mod) { type(d) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TvButton("⌫", modifier = Modifier.width(56.dp)) { digits = digits.dropLast(1) }
                    TvButton("0", modifier = Modifier.width(56.dp)) { type('0') }
                    TvButton("Cancel", modifier = Modifier.width(80.dp), onClick = onCancel)
                }
            }
        }
    }
}

private fun digitKey(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}
