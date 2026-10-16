package io.github.swikrutd.swidscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

val InkBlack = Color(0xFF111111)
val InkBlue = Color(0xFF1A3FA6)
val StampRed = Color(0xFFC62828)
val StampGreen = Color(0xFF2E7D32)

val SignatureInks = listOf(InkBlack, InkBlue)
val StampInks = listOf(StampRed, InkBlue, StampGreen, InkBlack)

/** Draws a light checkerboard so transparent areas are visible. */
fun Modifier.checkerboard(cell: Float = 16f): Modifier = drawBehind {
    drawRect(Color.White)
    val dark = Color(0xFFD9D9D9)
    var row = 0; var y = 0f
    while (y < size.height) {
        var col = 0; var x = 0f
        while (x < size.width) {
            if ((row + col) % 2 == 1) {
                drawRect(dark, Offset(x, y), androidx.compose.ui.geometry.Size(minOf(cell, size.width - x), minOf(cell, size.height - y)))
            }
            col++; x += cell
        }
        row++; y += cell
    }
}

/** The given [swatches] (black and blue by default) and a custom colour. */
@Composable
fun InkColorRow(color: Color, swatches: List<Color> = SignatureInks, onChange: (Color) -> Unit) {
    var custom by remember { mutableStateOf<Color?>(null) }
    var dialog by remember { mutableStateOf(false) }
    val isCustom = color !in swatches
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        swatches.forEach { Swatch(it, color == it, "Colour") { onChange(it) } }
        val cc = custom
        if (cc != null) Swatch(cc, isCustom, "Custom colour") { onChange(cc) }
        Text(
            if (cc == null) "Custom" else "Change",
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(CircleShape).clickable { dialog = true }.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
    if (dialog) {
        ColorPickerDialog(custom ?: color, onDismiss = { dialog = false }) { custom = it; onChange(it); dialog = false }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(color)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClickLabel = label, onClick = onClick),
    )
}

@Composable
fun ColorPickerDialog(initial: Color, onDismiss: () -> Unit, onPick: (Color) -> Unit) {
    val hsv = remember {
        val a = FloatArray(3)
        android.graphics.Color.colorToHSV(initial.toArgb(), a)
        a
    }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    val current = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Colour") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(40.dp).clip(CircleShape).background(current))
                Text("Hue", style = MaterialTheme.typography.labelMedium)
                Slider(h, { h = it }, valueRange = 0f..360f)
                Text("Strength", style = MaterialTheme.typography.labelMedium)
                Slider(s, { s = it })
                Text("Brightness", style = MaterialTheme.typography.labelMedium)
                Slider(v, { v = it })
            }
        },
        confirmButton = { TextButton(onClick = { onPick(current) }) { Text("Use") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
