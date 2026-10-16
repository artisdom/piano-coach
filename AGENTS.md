# AGENTS.md — Piano Coach for Android

Notes for AI agents (and people) picking up this project. Read this first,
then `README.md` (user-facing features). Keep this file current when you
change behaviour, tooling or test results.

## What this is

An Android tablet app (Kotlin, Jetpack Compose) that connects to the
**GZUT 88-key LED digital piano** and plays or teaches MIDI songs, with two
headline views: **Falling notes** and **Sheet music**. Modes: **Learn** (the
piano's LEDs light the next chord and the song waits), **Follow** (keys lit
ahead, song keeps going, adaptive guide) and **Listen**.

It is a port of the **Piano Coach firmware** for ESP32 boards in the
sibling repo `../piano_esp32` (Rust). Most logic in `core/` is a
line-by-line port of `../piano_esp32/firmware/piano-core/src/*.rs`; when
behaviour is unclear, the Rust original and its docs are the reference:

| Android (`app/src/main/java/org/pianocoach/`) | Firmware original |
|---|---|
| `core/Smf.kt` | `piano-core/src/smf.rs` |
| `core/Song.kt` | `song.rs` |
| `core/Engine.kt` | `engine.rs` (incl. adaptive Follow) |
| `core/Notation.kt` | `notation.rs` |
| `core/Synth.kt` | `synth.rs` |
| `core/Library.kt` | `library.rs` + `search.rs` |
| `core/Progress.kt` | `progress.rs` |
| `core/Controls.kt` | `controls.rs` |
| `assets/pinyin.tsv` | `pinyin_table.rs` (converted: `<hanzi>\t<pinyin>` per line) |
| `ui/SheetView.kt` colours, names | `firmware/piano-coach/src/ui/sheet.rs` |
| `ui/Theme.kt` palette | `firmware/piano-coach/ui/theme.slint` |

The firmware keeps evolving and features flow from it (e.g. adaptive
Follow, piano-key controls and shuffle came over in commit `9e8a8b5`). To
port a change, diff the Rust file since the last port and mirror it in the
Kotlin file of the same name, keeping names and comments parallel.

Key docs in the firmware repo:

* `../piano_esp32/doc/GZUT_piano_protocol.md` — **the piano's USB/BLE MIDI
  protocol** (read before touching `midi/`).
* `../piano_esp32/doc/piano_coach_firmware.md` — firmware features,
  architecture, verified results.

Remote: `git@github.com:artisdom/piano-coach.git`, branch `main` (the user
pushes). Commit only when the user asks.

## Repository layout

```
build.gradle.kts, settings.gradle.kts, gradle.properties   Gradle (AGP 8.13.1, Gradle 8.13, Kotlin 2.0.21)
app/build.gradle.kts         app module; copies the MIDI library into generated assets
app/src/main/
  AndroidManifest.xml        landscape, BLE + location permissions, MIDI/USB host features
  assets/pinyin.tsv          hanzi → toneless pinyin for search
  res/font/noto_music.ttf    Noto Music (OFL) — music glyphs for the sheet
  java/org/pianocoach/
    MainActivity.kt          screens (Library / Play / Settings), BLE permission requests,
                             file picker, immersive mode
    Coach.kt                 AndroidViewModel = the app: library index, player thread,
                             MIDI routing, piano links, boot song, control keys, shuffle
    Prefs.kt                 settings in SharedPreferences "prefs", Compose-observable
    core/                    pure Kotlin, no Android APIs (unit-testable on the JVM)
    midi/PianoLink.kt        link base class (send queue), PianoListener, MidiStreamParser
    midi/UsbMidiLink.kt      USB via android.media.midi
    midi/BleMidiLink.kt      BLE via raw GATT
    audio/SpeakerOut.kt      AudioTrack thread rendering core/Synth
    ui/                      Theme, LibraryScreen, PlayScreen, FallingNotes (+ Keyboard),
                             SheetView, SettingsScreen
app/src/test/java/org/pianocoach/core/CoreTest.kt   JVM tests incl. whole-library run
```

## Build, test, install

Environment on the user's Linux machine (`/home/nws`):

* JDK: use Android Studio's JBR — `export JAVA_HOME=~/android-studio/jbr`
  (JDK 21). The system Java is 25, which Gradle 8.13 can't run on.
* Android SDK: `~/Android/Sdk` (`local.properties` has `sdk.dir`, not in git).
  compileSdk/targetSdk 36, **minSdk 23** (android.media.midi needs it).
* adb: use `~/Android/Sdk/platform-tools/adb`; `/usr/bin/adb` is an older
  Debian build.
* Song library: `../midi-piano/assets/midi` (2449 `.mid` files, 44 MB,
  JustPiano pack in `JustPiano2-20160326/<category>（count）/…`). It is
  **not in git**; the `syncMidiAssets` task copies it into
  `app/build/generated/midiAssets/midi` (case-insensitive extension filter —
  one file is `.MID`). Override with `-PmidiLibrary=/path`.

```bash
export JAVA_HOME=~/android-studio/jbr
./gradlew assembleRelease            # R8-minified, signed with the debug key
./gradlew testDebugUnitTest          # ~30 s; parses+plays+engraves all 2449 songs
~/Android/Sdk/platform-tools/adb -s <serial> install -r app/build/outputs/apk/release/app-release.apk
```

* **Install release builds on the tablets.** Debug Compose builds are very
  slow on the Lenovo (first frame ~6 s vs ~1.2 s release). Release and
  debug share the debug signing key, so `install -r` upgrades either way.
* APK is ~38 MB (mostly the song library).
* `CoreTest.wholeLibrary` reads `../../midi-piano/assets/midi` relative to
  `app/` (or `-DmidiLibrary`); it skips quietly if the folder is missing.

## Test hardware

Two tablets on USB adb (landscape-only app):

| Serial | Device | Android | Screen | Notes |
|---|---|---|---|---|
| `HGT40X0C` | Lenovo Tab M10 TB-X505F (Snapdragon 429) | 10 / API 29 | 800×1280 px @160 dpi → **1280×800 dp** | slow CPU; BLE scan needs location permission **and Location on** |
| `zc09202289628318` | F888 (Unisoc T616) | 13 / API 33 | 1200×1920 px, density override 300 → **1024×640 dp** | smallest layout target; often asleep/locked; Chinese UI; a Huawei HMS service crashes in logcat (not ours) |

Every layout must fit **1024×640 dp** (the F888). Check both tablets after
UI changes.

Useful adb recipes (pixel coordinates are device pixels; take a screenshot
first, layouts change):

```bash
A="$HOME/Android/Sdk/platform-tools/adb -s zc09202289628318"
$A shell 'input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard'      # wake + unlock (no PIN)
$A shell am start -n org.pianocoach/.MainActivity
$A exec-out screencap -p > shot.png                                 # then view the PNG
$A shell input tap X Y ; $A shell input text mbg                    # drive the UI
$A logcat -d -s Coach BleMidiLink Sheet AndroidRuntime:E            # app logs
$A shell pm grant org.pianocoach android.permission.BLUETOOTH_SCAN  # API 31+ (also _CONNECT)
$A shell pm grant org.pianocoach android.permission.ACCESS_FINE_LOCATION  # API ≤ 30
```

`dumpsys window | grep mCurrentFocus` tells whether the app or the
keyguard is in front. Swiping the F888 lock screen via `input swipe` did not
work; `wm dismiss-keyguard` did.

## The piano (GZUT, from the protocol doc)

* USB: class-compliant MIDI `GZU-tek CHMidi-2.3`, VID:PID `5022:0716`.
  **Drops events from bursts** — send ≤ 8 messages per write, ~12 ms apart
  (`UsbMidiLink` does this).
* BLE: name `GZUT-MIDI` (public addr `7E:5F:FA:08:FC:BF`), service
  `03b80e5a-ede8-4b33-a751-6ce34ec4c700`, characteristic
  `7772e5db-3868-4112-a1a9-f2669d106bf3` (write-without-response + notify).
  **Every packet must start with exactly `80 80`** followed by plain MIDI
  (no per-message timestamps) — the piano drops anything else, which is why
  Android's `MidiManager.openBluetoothDevice` (timestamped) can't be used and
  `BleMidiLink` talks GATT directly. Keep writes ≤ 244 bytes.
* `GZUT-MUSIC` (`…:FC:BE`) is the separate BT-classic audio sink; it
  advertising means the piano is on.
* **One BLE central at a time.** While anything holds it (an ESP32 board,
  the other tablet, a phone), `GZUT-MIDI` stops advertising.
* LEDs: Note On lights a key; **velocity 1 = LED only, silent** (≥ 16
  audible); Note Off clears it; `B0 7B 00` (CC 123) clears all. Host LEDs
  ignore the physical keys.
* Piano → host: `90 nn vv` key down, `80 nn 00` key up (channel 1),
  vendor SysEx `F0 52 4B pp vv 00 00 F7` for panel changes
  (`2A` volume, `2B` metronome, `15` tone/home) — shown under Settings →
  Panel.
* Check BLE advertising from the Linux host:
  `timeout 15 bluetoothctl --timeout 12 scan on | grep -i 'FC:B'`.

## Architecture notes

**Threads and locking (`Coach.kt`).**

* `player` thread ticks the `Engine` every 2 ms while playing (15 ms
  idle) under `synchronized(lock)`. All engine access goes through `lock`.
* The engine appends packed messages (`status<<16 | d1<<8 | d2`, see
  `msg()` / `MsgList`) to `out`; `flush()` copies them out under the lock and
  **routes** them:
  * hint (Note On with vel ≤ `hintVel`, i.e. 1) → piano only;
  * audible Note On → piano (or a vel-1 copy so the LEDs still follow when
    sound is Tablet-only) and/or the speaker synth, per `SoundOut`
    (Auto = piano if connected, else tablet);
  * everything else → both.
* Link threads (`usb-midi-out`, `ble-midi-out`) drain each link's queue
  with the pacing the transport needs. `PianoListener.onKey` arrives on link
  threads → `keyInput()` (control keys first, then `engine.keyDown`).
* The UI reads a `Snapshot` (copied under the lock) once per frame via
  `withFrameMillis` in `PlayScreen`.
* Compose state (`mutableStateOf`) is written from background threads in a
  few places (`result`, `toast`, `libraryReady`); this works with the
  global snapshot but be careful adding more.

**Engine (`core/Engine.kt`).** Pure state machine: `play/pause/stop/seek/
tick/keyDown/keyUp/setOptions/setLoop/setTempo`. A sorted timeline of
timed outputs; hint and sounding notes are ref-counted per key so
overlapping notes never leave a hint dark. Learn halts at each `Step`
(notes within 40 ms = one chord). A–B loops ≥ 500 ms.
**Follow** (ported from the firmware's `engine.rs`) plays the whole song;
a tracked hand becomes "played by the learner" from the first hit on one of
its notes until 4 s (`FOLLOW_IDLE_MS`, playing time) without a hit, during
which its notes sound softly (40 % velocity, clamped 24–48) and it is
scored (hits −250/+300 ms); otherwise full velocity and unscored.

**Startup and piano-key remote (`Coach.kt`, `core/Controls.kt`).** Every
launch loads a random song from folder `儿时回忆` in **Follow** and starts it
as soon as a piano link is up (or immediately if sound is Tablet-only). The
lowest/highest keys (A0–G#1, D7–C8) are buttons — see the table in
`README.md` / `Controls.kt` — usable whenever nothing plays, and mid-song
only if the song never uses that key. E1/F1 step the top-level
categories (`categoryStep`) and play a random song from anywhere in one
(`songsBelow`). G#1 toggles shuffle-play of the current folder
(`Shuffle`, next song 2.5 s after one finishes).

**Library (`Coach.buildLibrary`).** Walks `assets/midi` with
`AssetManager.list` on a background thread (~0.7–2 s); a name ending in a
MIDI extension is a file, anything else a folder. A single top-level folder
(the JustPiano pack) is shown as the root (`rootAlias`). Search keys are
built from `pinyin.tsv` (full pinyin + initials; `mbg` → 卖报歌). Progress
(bookmarks, best Learn/Follow %, plays, recency) is
`filesDir/progress.tsv`, same format as the firmware's.

**Sheet music (`core/Notation.kt`, `ui/SheetView.kt`).** Engraves on
`Dispatchers.Default` (46 ms for 卖报歌 on the F888; slowest library song
176 ms on the PC). Coordinates are pixels with `space` = staff space; glyphs
are Noto Music characters drawn with `textSize = 4 × space` (font baseline =
staff bottom line, one staff space = ¼ em — the engraver's glyph widths
depend on this). Two systems' height sets the staff space (7–16 dp); the
view shows the current system at the top and turns as the cursor moves, so
the time signature only appears when the first system is on screen. Tap =
seek. Limits (same as firmware): 16th grid, one voice per staff, no
dynamics/pedal/8va.

**Falling notes (`ui/FallingNotes.kt`).** 3 s look-ahead; keyboard zoomed
to the song's range (≥ 28 keys) unless disabled; red = play now (Learn
step), teal/violet = right/left hand; on-screen keys are multi-touch and
play the tablet synth.

**Settings (`Prefs.kt`).** `save()` writes *every* setting, so stored
values can't be told from untouched defaults. To change a default for
existing installs, bump `Prefs.VERSION` and reset that setting when
`fresh` (see version 2: sheet note names → C D E, colours → Boomwhackers).

## UI conventions

* Palette in `ui/Theme.kt` (`C.*`), from the firmware: **red is reserved
  for LED hints / "play now"**; teal right hand, violet left hand, blue =
  your presses / cursor, amber = wrong note / loop.
* Immersive full screen; bars re-hidden in `onWindowFocusChanged` (Android
  10 kept reporting bar insets, so only display-cutout padding is applied).
* Material 3 text has a 24 sp default line height; rows that stack two
  lines in 50 dp need explicit `lineHeight` (see `StatusText`).
* Keep text fields' placeholders on one line (`maxLines = 1`) — they wrap on
  the F888.

## Verified status (2026-10-16)

| Check | Lenovo (A10) | F888 (A13) |
|---|---|---|
| Library browse, pinyin search, song card | ✔ | ✔ |
| Learn / Follow / Listen with on-screen keyboard + tablet speaker | ✔ | ✔ |
| Falling notes, sheet music (Boomwhackers + C D E defaults) | ✔ | ✔ |
| BLE auto-connect to `GZUT-MIDI` | ✔ MTU 247 | ✔ MTU 260 |
| All 88 LEDs on/off (Settings test buttons) | ✔ user-confirmed | ✔ user-confirmed |
| Learn on the piano: LEDs light, key presses advance | ✔ user-confirmed | ✔ user-confirmed |
| Panel SysEx received | ✔ | — |
| E1 / F1 step the top-level categories (208e4f4): 5× F1 then 2× E1 | not tried (PIN-locked, BT off) | ✔ user-confirmed; ended on 红色歌谣 as expected |
| USB MIDI to the piano | **not tried** | **not tried** |

Unit tests: 2449/2449 library songs parse, finish Learn, engrave.

## Known issues and gotchas

* **BLE reconnect after an unclean drop.** If the app is killed while
  connected (force-stop, `install -r`), the piano may ignore connection
  requests for 30 s – ~2 min: Android logs GATT status 133 / HCI
  "Connection Accept Timeout". `BleMidiLink` retries (rescan + reconnect
  2 s after each failure) and gets there. A 10 s connect watchdog was tried
  and made it *worse* (reverted). Signal at the PC was weak (−81…−99 dBm).
  Other occasions connected in ~15 s.
* Switching tablets: the piano accepts one central, so close the app (or
  BT) on one tablet before expecting the other to connect.
* Android ≤ 11 BLE needs ACCESS_FINE_LOCATION + Location enabled
  (status text says so); 12+ needs BLUETOOTH_SCAN/CONNECT. Permissions are
  requested in `MainActivity` whenever Bluetooth is enabled in Settings.
* API 33 changed GATT callbacks/writes; `BleMidiLink` handles both
  (`onCharacteristicChanged` 3-arg vs deprecated 2-arg, `writeCharacteristic`
  variants). Keep both paths when editing.
* `MidiManager.devices` / `registerDeviceCallback(cb, handler)` are
  deprecated on API 33 but still work; warnings in the build are expected.
* USB and the adb cable share the tablet's single port: testing USB MIDI
  needs adb over Wi-Fi (`adb tcpip 5555`) or logs read afterwards.
* The ESP32 boards from `../piano_esp32` may hold the piano's BLE link if
  they're powered.

## Ideas / not done

* USB MIDI hardware test; a USB_DEVICE_ATTACHED intent filter to launch on
  plug-in.
* Song globe and other Tab5-firmware UI features not ported yet.
* Snap A–B loop points to barlines (the sheet knows the bars).
* Baseline profile for faster cold start on the Lenovo.
