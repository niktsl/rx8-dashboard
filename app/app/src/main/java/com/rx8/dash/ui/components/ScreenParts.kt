package com.rx8.dash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rx8.dash.ui.theme.LocalDashColors

/** Draws an accent ring while this element (or the focus target right after it in the chain) has focus. */
@Composable
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(16.dp)): Modifier {
    val c = LocalDashColors.current
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .border(
            width = if (focused) 3.dp else 0.dp,
            color = if (focused) c.accent else Color.Transparent,
            shape = shape,
        )
}

/** Screen frame: title row (with optional [actions]) above a vertically scrolling body. */
@Composable
fun ScreenColumn(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalDashColors.current
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = c.text,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val c = LocalDashColors.current
    Text(
        text = text,
        color = c.accent,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        modifier = modifier.padding(top = 4.dp),
    )
}

/** Large-target button. [outlined] renders the low-emphasis variant, [danger] the warning colour. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    outlined: Boolean = false,
    danger: Boolean = false,
) {
    val c = LocalDashColors.current
    val shape = RoundedCornerShape(24.dp)
    val m = modifier.heightIn(min = 56.dp).focusRing(shape)
    val inner: @Composable () -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = m,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) c.warning else c.text),
        ) { inner() }
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = m,
            colors = if (danger) {
                ButtonDefaults.buttonColors(containerColor = c.warning, contentColor = Color.Black)
            } else {
                ButtonDefaults.buttonColors()
            },
        ) { inner() }
    }
}

/** Numeric text field with a decimal or integer keyboard. */
@Composable
fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    decimal: Boolean = true,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        suffix = if (suffix != null) ({ Text(suffix) }) else null,
        modifier = modifier,
    )
}

/** Parses user-typed numbers ("12,5" and "12.5" both work). Null for blank, NaN or infinite input. */
fun parseNum(text: String): Double? {
    val d = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    return if (d.isNaN() || d.isInfinite()) null else d
}

/** Whole-row toggle with a switch; the entire row is the touch/knob target. */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LocalDashColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusRing(RoundedCornerShape(12.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = c.textDim, fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .border(1.dp, color, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color? = null) {
    val c = LocalDashColors.current
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.textDim, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor ?: c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Dismissable one-line status message; renders nothing for a null [message]. */
@Composable
fun MessageBar(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (message == null) return
    val c = LocalDashColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.surfaceHigh, RoundedCornerShape(12.dp))
            .border(1.dp, c.info, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = c.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
    }
}

/** Full-size placeholder used while settings load. */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    val c = LocalDashColors.current
    Column(
        modifier = modifier.fillMaxSize().background(c.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Loading...", color = c.textDim, fontSize = 18.sp)
    }
}
