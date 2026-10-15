package org.pianocoach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.pianocoach.Coach
import org.pianocoach.SoundOut

@Composable
fun SettingsScreen(coach: Coach, onBack: () -> Unit) {
    val p = coach.prefs
    fun changed() = coach.settingsChanged()
    Column(Modifier.fillMaxSize().background(C.bg)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text) }
            Text("Settings", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Row(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Section("Piano") {
                    Line("USB", coach.usbState)
                    Toggle("Bluetooth (GZUT-MIDI)", p.bluetooth) { coach.setBluetooth(it) }
                    Line("", coach.bleState)
                    if (coach.panel.isNotEmpty()) Line("Panel", coach.panel)
                    Text(
                        "Plug the piano into the tablet's USB port (USB-C or an OTG adapter), or turn on " +
                            "Bluetooth. USB is used when both are available. The piano accepts one Bluetooth " +
                            "connection at a time — disconnect other phones or PCs first.",
                        color = C.faint, fontSize = 13.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { coach.lightAll(true) }, colors = ButtonDefaults.buttonColors(containerColor = C.raised, contentColor = C.text)) {
                            Text("Light all keys")
                        }
                        Button(onClick = { coach.lightAll(false) }, colors = ButtonDefaults.buttonColors(containerColor = C.raised, contentColor = C.text)) {
                            Text("All LEDs off")
                        }
                    }
                }
                Section("Sound") {
                    Text("Song notes sound from", color = C.dim, fontSize = 15.sp)
                    Segmented(SoundOut.entries, p.sound, label = { it.label }, onSelect = { p.sound = it; changed() })
                    Text(
                        "Auto: the piano when connected, else the tablet. With Tablet the piano's LEDs still " +
                            "follow the notes silently. Your own playing always sounds on the piano.",
                        color = C.faint, fontSize = 13.sp,
                    )
                    Text("Tablet volume", color = C.dim, fontSize = 15.sp)
                    Slider(value = p.volume, onValueChange = { p.volume = it; coach.speaker.volume = it }, onValueChangeFinished = { changed() })
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Section("Practice") {
                    Toggle("Piano plays the other hand", p.accompany) { p.accompany = it; changed() }
                    Toggle("Easy mode (any key advances)", p.easy) { p.easy = it; changed() }
                    Toggle("Hint LED off when its key is hit", p.ledOffOnHit) { p.ledOffOnHit = it; changed() }
                    Text("Follow: light keys ${p.leadMs} ms ahead", color = C.dim, fontSize = 15.sp)
                    Slider(
                        value = p.leadMs.toFloat(), valueRange = 200f..1500f, steps = 12,
                        onValueChange = { p.leadMs = (Math.round(it / 100f) * 100) }, onValueChangeFinished = { changed() },
                    )
                }
                Section("Falling notes") {
                    Toggle("Zoom keyboard to the song's range", p.fitRange) { p.fitRange = it; changed() }
                    Toggle("Note names on bars and keys", p.rollNames) { p.rollNames = it; changed() }
                }
                Section("Sheet music") {
                    Text("Note colours", color = C.dim, fontSize = 15.sp)
                    Segmented(listOf(1, 2, 0), p.sheetColors, label = {
                        when (it) {
                            1 -> "Boomwhackers"
                            2 -> "Rainbow"
                            else -> "Ink"
                        }
                    }, onSelect = { p.sheetColors = it; changed() })
                    Text("Note names in the heads", color = C.dim, fontSize = 15.sp)
                    Segmented(listOf(0, 1, 2), p.noteNames, label = {
                        when (it) {
                            1 -> "C D E"
                            2 -> "Do Re Mi"
                            else -> "None"
                        }
                    }, onSelect = { p.noteNames = it; changed() })
                    Toggle("Keyboard under the sheet", p.sheetKeyboard) { p.sheetKeyboard = it; changed() }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.accent))
    }
}

@Composable
private fun Line(k: String, v: String) {
    Row {
        Text(k, color = C.faint, fontSize = 15.sp, modifier = Modifier.width(80.dp))
        Text(v, color = C.text, fontSize = 15.sp)
    }
}
