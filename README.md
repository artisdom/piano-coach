# Piano Coach for Android

Play and learn MIDI songs on the GZUT 88-key LED piano from an Android
tablet — the Android port of the Piano Coach firmware in
[`piano_esp32`](../piano_esp32) (`firmware/piano-core`).

* **Falling notes** — notes fall onto a keyboard zoomed to the song's
  range (teal right hand, violet left hand, red = play now; the piano's
  LEDs light the same keys).
* **Sheet music** — a grand staff engraved from the MIDI file (clefs, key
  and time signatures, beams, ties, rests, accidentals), note heads
  coloured Boomwhackers (default) / Rainbow / Ink and named C D E
  (default), Do Re Mi or not at all, a cursor that follows the music and
  turns the page. Tap the music to jump there.
* **Learn** (the piano lights the next chord and waits), **Follow** and
  **Listen**; Both / Right / Left hand, tempo 25–200 %, A–B practice loop,
  scores with stars, bookmarks and recently played.
* **Follow** plays the song like Listen with the keys lit just ahead. Join
  in with a hand and, from your first hit on one of its notes, the piano
  plays that hand softly (40 % velocity) as a guide while the other stays
  at full volume; play both and both go soft. After 4 s without a hit on a
  hand it is loud again. Only hands you are playing are scored (hits within
  −250/+300 ms). Hands = Right or Left tracks only that hand.
* **The piano is the remote.** The app opens in Follow with a random
  儿时回忆 song, which starts as soon as the piano connects. The lowest and
  highest keys work as buttons (whenever the song doesn't use them):

  | Low end | | High end | |
  |---|---|---|---|
  | A0 / B0 | previous / next song | D7 / E7 / F7 | Left / Both / Right hand |
  | A#0 / C#1 | tempo −10 / +10 % | G7 / A7 / B7 | Learn / Follow / Listen |
  | C1 | play / pause | C8 | bookmark |
  | D1 | restart | | |
  | E1 / F1 | previous / next category (random song from it) | | |
  | G1 | random song in the folder | | |
  | G#1 | shuffle-play the folder (on / off) | | |

  Shuffle-play plays every song of the folder once in random order (the
  current one first, the next 2.5 s after one ends); B0 / A0 skip within
  it, E1 / F1 jump to a song of the next category and shuffle its folder,
  picking a song any other way ends it. Categories are the library's
  top-level folders (儿时回忆, 影视剧场, 流行空间, 红色歌谣, 经典乐章).
  Same map as the firmware (`piano-core/src/controls.rs`).
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
core/      Smf, Song, Engine, Notation, Synth, Library (search), Progress, Controls — ports of piano-core
midi/      UsbMidiLink, BleMidiLink, MidiStreamParser
audio/     SpeakerOut (AudioTrack thread for the synth)
Coach.kt   library index, player thread (engine at ~500 Hz), MIDI routing, settings
ui/        Library, Play (FallingNotes, SheetView, Keyboard), Settings
```

## Tested on

| Tablet | Android | Screen (dp) | Result |
|---|---|---|---|
| Lenovo Tab M10 TB-X505F | 10 (API 29) | 1280×800 | library, search, Learn/Follow/Listen, falling notes, sheet, settings ✔; **Bluetooth to the piano ✔** (auto-connect, MTU 247, all 88 LEDs on/off, Learn lights chords and advances on key presses, panel SysEx received) |
| F888 | 13 (API 33) | 1024×640 | library, search, Learn/Follow/Listen, falling notes, sheet, settings ✔; **Bluetooth to the piano ✔** (auto-connect, MTU 260 — reconnecting after the app was killed took 30 s to ~2 min, see notes; all 88 LEDs on/off; Learn lights chords and advances) |

USB to the piano is not tried yet.

## Notes

* The piano accepts one Bluetooth LE connection: while an ESP32 board,
  phone or PC holds it, `GZUT-MIDI` stops advertising and the tablet keeps
  searching.
* After the app is killed while connected (force-stop, reinstall), the
  piano can ignore connection requests for 30 s to ~2 min (Android reports
  GATT status 133, "Connection Accept Timeout"); the app keeps retrying
  and connects by itself. A weak signal makes this worse — keep the tablet
  near the piano. Cutting Android's 30 s attempt shorter was tried and did
  not help.
* Android ≤ 11 needs the location permission **and Location turned on** for
  Bluetooth scans; Android 12+ asks for "Nearby devices".
* Progress is kept in the app's files as `progress.tsv`, the same format as
  the firmware's `.pianocoach/progress.tsv`.
