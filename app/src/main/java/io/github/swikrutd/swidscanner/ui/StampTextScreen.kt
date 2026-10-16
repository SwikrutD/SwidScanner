package io.github.swikrutd.swidscanner.ui

import android.graphics.Bitmap
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.swikrutd.swidscanner.data.AssetKind
import io.github.swikrutd.swidscanner.data.AssetStore
import io.github.swikrutd.swidscanner.imaging.StampRender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.roundToInt

/** A text stamp to start from. */
private enum class StampTemplate(val text: String, val color: Color) {
    APPROVED("APPROVED", StampGreen),
    PAID("PAID", StampGreen),
    RECEIVED("RECEIVED", InkBlue),
    COPY("COPY", InkBlue),
    DRAFT("DRAFT", StampRed),
    CONFIDENTIAL("CONFIDENTIAL", StampRed),
}

private fun today(ctx: android.content.Context): String = DateFormat.getDateFormat(ctx).format(Date())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StampTextScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var template by remember { mutableStateOf<StampTemplate?>(StampTemplate.APPROVED) }
    var text by remember { mutableStateOf(StampTemplate.APPROVED.text) }
    var color by remember { mutableStateOf(StampTemplate.APPROVED.color) }
    var withDate by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(today(ctx)) }
    // Until the date is edited it is refreshed when the stamp is saved, so it is the day of saving.
    var dateEdited by remember { mutableStateOf(false) }
    var border by remember { mutableFloatStateOf(8f) }
    var tilt by remember { mutableFloatStateOf(-6f) }
    var saving by remember { mutableStateOf(false) }

    val dateLine = if (withDate) date else null
    val preview by produceState<Bitmap?>(null, text, dateLine, color, border, tilt) {
        value = withContext(Dispatchers.Default) {
            StampRender.render(text.split('\n'), dateLine, color.toArgb(), border, tilt)
        }
    }
    val canSave = !saving && (text.isNotBlank() || (withDate && date.isNotBlank()))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Text stamp") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TransparentPreview(
                Modifier.fillMaxWidth().height(200.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                bitmap = preview, emptyText = if (preview == null && !canSave) "Type some text" else null,
            )
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Start from", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StampTemplate.entries.forEach { t ->
                        FilterChip(template == t, {
                            template = t; text = t.text; color = t.color
                        }, label = { Text(t.text) })
                    }
                }
                OutlinedTextField(
                    text, { text = it; template = StampTemplate.entries.firstOrNull { t -> t.text == it } },
                    Modifier.fillMaxWidth(), label = { Text("Text") }, minLines = 1, maxLines = 3,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Add today's date", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Switch(withDate, { withDate = it })
                }
                if (withDate) {
                    OutlinedTextField(
                        date, { date = it; dateEdited = true },
                        Modifier.fillMaxWidth(), label = { Text("Date line") }, singleLine = true,
                    )
                }
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                InkColorRow(color, StampInks) { color = it }
                Text("Border ${border.roundToInt()}", style = MaterialTheme.typography.labelLarge)
                Slider(border, { border = it }, valueRange = 2f..20f)
                Text("Tilt ${tilt.roundToInt()} degrees", style = MaterialTheme.typography.labelLarge)
                Slider(tilt, { tilt = it }, valueRange = -StampRender.MAX_TILT..StampRender.MAX_TILT)
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.End) {
                Button(enabled = canSave, onClick = {
                    saving = true
                    scope.launch {
                        val line = if (withDate) (if (dateEdited) date else today(ctx)) else null
                        val bmp = withContext(Dispatchers.Default) {
                            StampRender.render(text.split('\n'), line, color.toArgb(), border, tilt)
                        }
                        if (bmp != null) AssetStore.add(AssetKind.STAMP, bmp, text.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty())
                        nav.popBackStack()
                    }
                }) { Text("Save") }
            }
        }
    }
}
