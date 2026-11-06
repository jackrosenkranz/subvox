# Subvox on Android

The Android app (`android/`) is a Jetpack Compose app on the shared Kotlin `core`, with the same
three screens as the macOS app. Minimum Android 8.0 (API 26); head tracking needs Android 13.

## Motion sources

| Choice | What it is | When it streams |
|---|---|---|
| Headset head tracker | `Sensor.TYPE_HEAD_TRACKER`: rotation vector and angular velocity from a headset that reports head tracking through Android's spatializer (Pixel Buds Pro on Pixel phones, some LE Audio headsets) | Android 13+, tracking headset connected. Never with a Shokz headset, which has no motion sensor |
| Phone IMU | The phone's own gyroscope, rotation vector and linear acceleration | Always. For testing the pipeline with the phone pressed to the jaw or strapped to a headband; it is not headphone motion and sessions are tagged `android/Phone IMU` |
| Replay last session | The newest recorded session's `motion.csv` | When a session exists |
| None | Shows why nothing streams | |

The head tracker carries no linear acceleration, so the ACCELERATION feature is empty with it;
evaluate with Rotation and Attitude.

**Forward to desktop** sends every sample to a Windows machine running the desktop app with
its UDP receiver on, so the Windows Train and Decode screens can run on live phone motion.

## Microphone

Pick the input on the Sensors screen. A Bluetooth headset's microphone (a Shokz, for example)
is listed as `<name> (Bluetooth headset mic)`; choosing it routes audio over the SCO link
(`setCommunicationDevice` on Android 12+, `startBluetoothSco` before that), 16 kHz or 8 kHz
mono, with the headset's own noise processing already applied. The app shows the recognized
headset's spec-sheet microphone data and motion capability.

The reference transcript uses Android's `SpeechRecognizer` (on-device when the phone offers
it). The recognizer opens the microphone itself and cannot be fed the app's buffers, so on some
phones the app's own capture goes silent while it runs. Record session audio or transcribe, not
both, unless the phone is known to allow concurrent capture.

## Capture key

Hold the on-screen button, or hold **volume-down**, to capture a take on Train, decode a
segment on Decode, or mark an utterance while recording.

## Files

- Sessions: `Android/data/dev.subvox.android/files/Sessions/<timestamp>-<condition>/` with
  `motion.csv`, `events.csv`, `audio.wav`, `meta.json`, readable over USB. **Import last session
  takes** turns its labelled marks into templates.
- Training set: app-private `dataset.json`, the same JSON the macOS and Windows apps write.

## Build

Needs Android Studio or the command-line SDK with API 35. The module is only included when an
SDK location is known (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `local.properties` with
`sdk.dir`), so desktop-only machines build without it.

```sh
./gradlew :android:assembleDebug
./gradlew :android:installDebug
```

Permissions requested at launch: microphone, and on Android 12+ `BLUETOOTH_CONNECT` (needed to
list and route to a Bluetooth headset microphone).

## Status

The Android module was written against the same core as the Windows app but has not been
compiled or run in this repository's CI, which has no Android SDK. Expect to fix compile
errors in the Compose screens on first build. The core logic it calls is covered by the
`core` tests.
