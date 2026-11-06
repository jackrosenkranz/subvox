# Subvox on Windows

The Windows app is the JVM desktop module (`desktop/`): the same three screens as the macOS
app, built on the shared Kotlin `core`. It also runs on Linux and on macOS (where it shares
`~/Library/Application Support/Subvox/dataset.json` with the SwiftUI app).

## What works on Windows

- **Motion.** Windows has no headphone-motion API, and Shokz headsets have no motion sensor
  (see `docs/HEADSETS.md`). Live motion comes from one of two sources:
  - **Replay** a session folder recorded on macOS or Android (`motion.csv`). Replay is
    re-stamped onto the local clock, so capture, decoding and recording behave as with a live
    headset. `--speed` plays faster or slower.
  - **UDP** from the Android app (`Forward to desktop`): one `motion.csv` row per datagram on
    port 55055. Allow the app through the Windows firewall on first use.
- **Microphone.** Any Windows input, including a Shokz headset's `Headset (<model> Hands-Free AG
  Audio)` endpoint. The app lists only microphone-capable endpoints, picks a Shokz automatically
  when one is present, asks for 16 kHz mono first (the HFP link's native format) and falls back
  to 48/44.1 kHz, and shows the headset's spec-sheet microphone data when it recognizes the name.
- **Reference transcript.** Offline recognition with Vosk. The model is not bundled: download
  `vosk-model-small-en-us-0.15` from <https://alphacephei.com/vosk/models>, unpack it, and put
  the folder at `%APPDATA%\Subvox\vosk-model` (or set `SUBVOX_VOSK_MODEL` to its path). Audio is
  downmixed and resampled to 16 kHz before recognition.
- **Recording.** `Documents\Subvox\Sessions\<timestamp>-<condition>\` with `motion.csv`,
  `events.csv`, `audio.wav` (16-bit PCM at the capture rate) and `meta.json`, the macOS layout.
  **Import session takes** turns a session's labelled marks into training templates.
- **Training set.** `%APPDATA%\Subvox\dataset.json`, byte-compatible with the macOS file (copy it
  across to reuse templates).

## Build and run

Requires a JDK 17 or newer on the PATH (Temurin 21 is fine). From the repository root:

```powershell
.\gradlew.bat :desktop:run
```

Options after `--args=`:

```powershell
.\gradlew.bat :desktop:run --args="--replay 'C:\Users\you\Documents\Subvox\Sessions\20261106T090000-silent' --screen decode --auto"
```

| Option | Meaning |
|---|---|
| `--replay <folder>` | Open with a recorded session playing as the motion source |
| `--speed N` | Replay speed multiplier |
| `--import <folder>` | Add the session's labelled marks as templates before the window opens |
| `--screen sensors|train|decode` | Starting screen |
| `--udp` | Start the UDP receiver |
| `--auto` | Decode in auto-detect mode and calibrate immediately |

To make a self-contained folder (`desktop\build\install\Subvox\bin\Subvox.bat`):

```powershell
.\gradlew.bat :desktop:installDist
```

Tests for the shared core and the desktop module:

```powershell
.\gradlew.bat :core:test :desktop:test
```

## Keys

Hold **Space** to capture on the Train and Decode screens (and to mark utterances while
recording on Sensors). Text fields keep the space bar when focused. The large button does the
same with the mouse.
