package org.pianocoach

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class SoundOut(val label: String) {
    Auto("Auto"), Piano("Piano"), Speaker("Tablet"), Both("Both")
}

enum class ViewMode { Falling, Sheet }

/** Settings, saved in SharedPreferences and observable by Compose. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var bluetooth by mutableStateOf(sp.getBoolean("bluetooth", true))
    var sound by mutableStateOf(SoundOut.entries[sp.getInt("sound", 0).coerceIn(0, 3)])
    var volume by mutableFloatStateOf(sp.getFloat("volume", 0.8f))
    var accompany by mutableStateOf(sp.getBoolean("accompany", true))
    var easy by mutableStateOf(sp.getBoolean("easy", false))
    var ledOffOnHit by mutableStateOf(sp.getBoolean("ledOffOnHit", true))
    var leadMs by mutableIntStateOf(sp.getInt("leadMs", 700))

    /** 0 ink, 1 Boomwhackers, 2 rainbow. */
    var sheetColors by mutableIntStateOf(sp.getInt("sheetColors", 1))

    /** 0 none, 1 letters, 2 solfège. */
    var noteNames by mutableIntStateOf(sp.getInt("noteNames", 0))
    var view by mutableStateOf(ViewMode.entries[sp.getInt("view", 0).coerceIn(0, 1)])

    /** Falling notes: show only the song's key range. */
    var fitRange by mutableStateOf(sp.getBoolean("fitRange", true))

    /** Sheet view: keyboard strip under the music. */
    var sheetKeyboard by mutableStateOf(sp.getBoolean("sheetKeyboard", true))

    /** Falling notes: note names on the bars and keys. */
    var rollNames by mutableStateOf(sp.getBoolean("rollNames", true))

    fun save() {
        sp.edit()
            .putBoolean("bluetooth", bluetooth)
            .putInt("sound", sound.ordinal)
            .putFloat("volume", volume)
            .putBoolean("accompany", accompany)
            .putBoolean("easy", easy)
            .putBoolean("ledOffOnHit", ledOffOnHit)
            .putInt("leadMs", leadMs)
            .putInt("sheetColors", sheetColors)
            .putInt("noteNames", noteNames)
            .putInt("view", view.ordinal)
            .putBoolean("fitRange", fitRange)
            .putBoolean("sheetKeyboard", sheetKeyboard)
            .putBoolean("rollNames", rollNames)
            .apply()
    }
}
