# Subvox

A prototype that tests whether headphone head-motion data carries enough information to turn **silently mouthed words into text**. It began as a macOS app on AirPods Pro (`CMHeadphoneMotionManager`); this fork adds a Windows app and an Android app on a shared Kotlin core, so the same experiment runs where Shokz and other non-Apple headsets live.

It doesn't use a language model. It's a small-vocabulary template recognizer plus a statistical test that tells you whether the signal is real.

## Platforms

| Platform | App | Head motion | Microphone | Docs |
|---|---|---|---|---|
| macOS 14+ | `Subvox/` (SwiftUI) | AirPods Pro and other Apple headsets, live | Any input | this file |
| Windows 10/11 | `desktop/` (Kotlin, Swing) | Replay of a recorded session, or UDP from the Android app. Windows has no headphone-motion API | Any input, including a Shokz headset's hands-free endpoint | [docs/WINDOWS.md](docs/WINDOWS.md) |
| Android 8+ | `android/` (Kotlin, Compose) | Head-tracker sensor on Android 13+ with a tracking headset; the phone's own IMU otherwise | Any input, including a Shokz headset over SCO | [docs/ANDROID.md](docs/ANDROID.md) |

**Shokz headsets (OpenRun, OpenRun Pro 2, OpenComm2, OpenSwim Pro) have no motion sensor on any platform.** With them only the microphone channel is live; the motion stream has to come from a recording or from another device. Their microphone specs, the Bluetooth hands-free profile behaviour, and which headsets do provide motion are in [docs/HEADSETS.md](docs/HEADSETS.md).

The decoder, evaluation, capture logic, session format and dataset format live once, in `core/`, and are covered by tests (`./gradlew :core:test`). `dataset.json` and session folders are interchangeable across the three apps.

```sh
./gradlew :core:test :desktop:test   # shared core and Windows app tests
./gradlew :desktop:run                # Windows/Linux/macOS JVM app
./gradlew :android:assembleDebug      # needs an Android SDK; see docs/ANDROID.md
```

## macOS app

### Build & run

```sh
brew install xcodegen   # once
xcodegen generate
open Subvox.xcodeproj   # ⌘R
```

Or from the command line:

```sh
xcodebuild -project Subvox.xcodeproj -scheme Subvox -derivedDataPath build -allowProvisioningUpdates build
open build/Build/Products/Debug/Subvox.app
xcodebuild -project Subvox.xcodeproj -scheme Subvox -derivedDataPath build test
```

Requires macOS 14+, AirPods Pro or another headphone that supports head tracking, connected as the Mac's audio output. On first launch, grant **Motion**. The mic and speech prompts only appear when you start the mic.

### Screens

| Screen | What it does |
|---|---|
| **Sensors** | Live plots of user acceleration, rotation rate and attitude, the measured sample rate, a mic input picker and level meter, and a recorder for experiment sessions. |
| **Train** | Pick a word, hold **Space**, mouth it, release. Each take is stored as a template. **Evaluate** runs leave-one-out classification and a 500× label-permutation test, then shows accuracy against chance, a p-value and a confusion matrix. The feature checkboxes (acceleration / rotation / attitude) let you test which channels carry the signal. |
| **Decode** | The motion-to-text screen. Hold Space, or use auto-detect after calibrating the noise floor. Each segment is DTW-matched against your templates and added to the transcript. A live mic transcript runs alongside it for comparison. |

### How decoding works

1. **Segmentation** (`CaptureController`). Space-hold, or an energy trigger above a calibrated noise floor with pre-roll and hangover.
2. **Features** (`MotionFeatures`). Per frame: user acceleration (3), rotation rate (3), and attitude change since the start of the segment (3). Each channel is mean-centered, and quiet lead-in and lead-out frames are trimmed.
3. **Matching** (`DTW`, `TemplateClassifier`). Each channel is z-scaled across the training set. Matching is 1-nearest-neighbour per word, using dynamic time warping with a Sakoe–Chiba band. A segment is rejected if the best distance is above *max distance* or the margin to the runner-up is below *min confidence*.
4. **Is it real?** (`Evaluation`). The leave-one-out accuracy is compared with the same procedure run on shuffled labels. If p < 0.05, the motion stream distinguishes your words better than chance.

### Experiment protocol

Use **Record Session** in the toolbar or on the Sensors screen. Recordings go to `~/Documents/Subvox/Sessions/<timestamp>-<condition>/`:

- `motion.csv`: every motion sample. `t` is seconds from session start.
- `audio.caf`: microphone audio, if the mic is running.
- `events.csv`: every Space-held segment, with its label or prediction.
- `meta.json`: condition, prompt, and the audio start offset for aligning audio with motion.

Suggested runs: the same 5-word script under **Normal speech**, **Whisper** and **Silent mouthing**, 10 reps each. Then, in Train, compare the Evaluate results across feature sets.

The training set lives at `~/Library/Application Support/Subvox/dataset.json`.

### Known limits

- Headphone motion is processed and low-rate (roughly 25 Hz on AirPods). The speech-detecting accelerometer and the inward mic are not exposed. Expect jaw and head movement, not vocal vibration.
- Templates are user- and session-specific. Re-seating the AirPods can shift the signal.
- Choosing AirPods as the mic input switches them to the Bluetooth headset (HFP) profile.
- The Windows and Android apps share the session layout: `audio.wav` instead of `audio.caf`, and `meta.json` gains `headset`, `headsetMotion` and `platform`.
