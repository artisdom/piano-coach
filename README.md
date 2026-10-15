# Piano Coach for Android

Play and learn MIDI songs on the GZUT 88-key LED piano from an Android
tablet — the Android port of the Piano Coach firmware in
[`piano_esp32`](../piano_esp32) (`firmware/piano-core`).

* **Falling notes** — notes fall onto a keyboard zoomed to the song's
  range (teal right hand, violet left hand, red = play now; the piano's
  LEDs light the same keys).
* **Sheet music** — a grand staff engraved from the MIDI file (clefs, key
  and time signatures, beams, ties, rests, accidentals), note heads
  coloured Boomwhackers / Rainbow / Ink with optional C D E or Do Re Mi
  names, a cursor that follows the music and turns the page. Tap the
  music to jump there.
* **Learn** (the piano lights the next chord and waits), **Follow** (keys
  light ahead; hits within −250/+300 ms count) and **Listen**; Both / Right /
  Left hand, tempo 25–200 %, A–B practice loop, scores with stars, bookmarks
  and recently played.
* **Library**: the 2449-song JustPiano library is bundled; search by title,
  full pinyin (`xiaoxingxing`) or initials (`mbg` → 卖报歌). Any other
  `.mid` can be opened with **Open MIDI file**.
* **Connection**: USB (class-compliant MIDI via `android.media.midi`,
  paced ≤ 8 messages / 12 ms) or Bluetooth LE to `GZUT-MIDI` over raw GATT
  — every write is `80 80` + plain MIDI, because the piano drops
  timestamped BLE-MIDI packets (Android's own BLE-MIDI encoder cannot drive
  it). USB wins when both are present.
* **Sound**: song notes from the piano, the tablet (the firmware's
  additive piano synth), or both; with no piano the tablet plays and the
  on-screen keyboard works for Learn/Follow.

## Build and install

Needs the Android SDK (API 36) and JDK 17–21 (Android Studio's JBR works).
The MIDI library is copied into the APK from `../midi-piano/assets/midi`
(override with `-PmidiLibrary=/path/to/midi`).

```bash
export JAVA_HOME=~/android-studio/jbr
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Use the release build on slow tablets: R8 makes Compose several times
faster (first launch on a Snapdragon 429: ~6 s debug, ~1.2 s release).

Core tests (parser, engine, engraving of every library song):

```bash
./gradlew testDebugUnitTest
```

## Layout

```
core/      Smf, Song, Engine, Notation, Synth, Library (search), Progress — ports of piano-core
midi/      UsbMidiLink, BleMidiLink, MidiStreamParser
audio/     SpeakerOut (AudioTrack thread for the synth)
Coach.kt   library index, player thread (engine at ~500 Hz), MIDI routing, settings
ui/        Library, Play (FallingNotes, SheetView, Keyboard), Settings
```

## Tested on

| Tablet | Android | Screen (dp) | Result |
|---|---|---|---|
| Lenovo Tab M10 TB-X505F | 10 (API 29) | 1280×800 | library, search, Learn/Follow/Listen, falling notes, sheet, settings ✔; **Bluetooth to the piano ✔** (auto-connect, MTU 247, all 88 LEDs on/off, Learn lights chords and advances on key presses, panel SysEx received) |
| F888 | 13 (API 33) | 1024×640 | library, search, modes, both views, settings ✔ (on-screen keyboard and speaker; Bluetooth was off) |

USB to the piano is not tried yet.

## Notes

* The piano accepts one Bluetooth LE connection: while an ESP32 board,
  phone or PC holds it, `GZUT-MIDI` stops advertising and the tablet keeps
  searching.
* Android ≤ 11 needs the location permission **and Location turned on** for
  Bluetooth scans; Android 12+ asks for "Nearby devices".
* Progress is kept in the app's files as `progress.tsv`, the same format as
  the firmware's `.pianocoach/progress.tsv`.
