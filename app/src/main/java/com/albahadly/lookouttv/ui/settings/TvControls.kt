package com.albahadly.lookouttv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.albahadly.lookouttv.ui.Palette
import com.albahadly.lookouttv.ui.isSelect

/** A remote-friendly button: white when focused, accent when [primary]. */
@Composable
fun TvButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val background = when {
        focused -> Palette.TextBright
        primary -> Palette.Accent
        else -> Palette.Tile
    }
    val textColor = when {
        focused || primary -> Palette.OnAccent
        danger -> Palette.Error
        !enabled -> Palette.Quiet
        else -> Palette.Text
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent {
                if (enabled && it.type == KeyEventType.KeyUp && it.key.isSelect()) {
                    onClick(); true
                } else false
            }
            .focusable()
            .background(background, RoundedCornerShape(8.dp))
            .border(1.dp, if (focused || primary) background else Palette.Border, RoundedCornerShape(8.dp))
            .defaultMinSize(minHeight = 32.dp, minWidth = 64.dp)
            .padding(horizontal = 14.dp),
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = textColor)
    }
}

/** On/off switch; OK toggles it. */
@Composable
fun TvSwitch(checked: Boolean, label: String, onToggle: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .semantics { contentDescription = "$label: ${if (checked) "on" else "off"}" }
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp && it.key.isSelect()) {
                    onToggle(!checked); true
                } else false
            }
            .focusable()
            .border(2.dp, if (focused) Palette.TextBright else Palette.Background, RoundedCornerShape(12.dp))
            .padding(2.dp),
    ) {
        Box(
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
            modifier = Modifier
                .size(width = 36.dp, height = 20.dp)
                .background(if (checked) Palette.Accent else Palette.Border, RoundedCornerShape(10.dp))
                .padding(2.dp),
        ) {
            Box(Modifier.size(16.dp).background(Palette.TextBright, CircleShape))
        }
    }
}

/**
 * A text field for the remote: D-pad moves through fields, OK opens the TV keyboard,
 * the keyboard's Next button moves to the following field.
 */
@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    width: Dp? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 10.sp, color = Palette.Quiet)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Palette.TextBright, fontSize = 12.sp),
            cursorBrush = SolidColor(Palette.Accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = ImeAction.Next,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onNext = {
                keyboard?.hide()
                focusManager.moveFocus(FocusDirection.Next) // reading order: Username -> Password
            }),
            modifier = (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyUp && it.key.isSelect()) {
                        keyboard?.show(); true
                    } else false
                },
            decorationBox = { inner ->
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .background(Palette.Tile, RoundedCornerShape(8.dp))
                        .border(2.dp, if (focused) Palette.Accent else Palette.Border, RoundedCornerShape(8.dp))
                        .height(32.dp)
                        .padding(horizontal = 10.dp),
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, fontSize = 12.sp, color = Palette.Quiet.copy(alpha = 0.6f))
                    }
                    inner()
                }
            },
        )
    }
}

/** A row of mutually exclusive choices (e.g. TCP / UDP). */
@Composable
fun <T> TvChoice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            TvButton(label, primary = value == selected) { onSelect(value) }
        }
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        if (subtitle != null) Text(subtitle, fontSize = 10.sp, color = Palette.Quiet)
    }
}

@Composable
fun Hint(text: String, color: androidx.compose.ui.graphics.Color = Palette.Quiet) {
    Text(text, fontSize = 10.sp, color = color, modifier = Modifier.padding(top = 2.dp))
}
