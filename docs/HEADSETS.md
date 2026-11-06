# Headsets: what each one can give Subvox

Subvox needs two signals: head motion from the headset and a microphone for the reference
transcript. Not every headset provides both. This page records what is known about the headsets
the project has been run or planned with, so a session's `meta.json` and a reader's
expectations line up. Specs are from the makers' published sheets as of late 2026, not
measured here. The same table lives in code as `Headsets` in `core/.../Headsets.kt` and is
what the Windows and Android apps show when they recognize a device name.

## Platform matrix

| Platform | Headphone motion API | Microphone path | Reference speech-to-text |
|---|---|---|---|
| macOS 14+ | Core Motion `CMHeadphoneMotionManager` (Apple headsets only) | Core Audio, any input | Apple Speech, on-device |
| Windows 10/11 | None. Replay a recorded session, or receive UDP from the Android app | Java Sound (WASAPI/DirectSound); Bluetooth mic is the "Hands-Free AG Audio" endpoint | Vosk, offline, model downloaded by the user |
| Android 13+ | `Sensor.TYPE_HEAD_TRACKER` for headsets that report head tracking through the spatializer; otherwise the phone's own IMU | `AudioRecord`; Bluetooth mic over the SCO link | Android `SpeechRecognizer`, on-device when available |

## Shokz bone-conduction headsets

**No Shokz model exposes a motion sensor to the host.** On every platform the motion channel is
empty with a Shokz headset; only the microphone channel carries data. The apps say so in the
motion status line instead of waiting for samples.

The microphone reaches the host over the Bluetooth Hands-Free Profile (HFP): mono, 16 kHz
wideband (mSBC) or 8 kHz (CVSD), with the headset's own Qualcomm CVC noise reduction applied
before the host sees the signal. While the mic is open, playback drops to the same call codec.
On Windows the mic is the endpoint named `Headset (<model> Hands-Free AG Audio)`; the
`Headphones (<model> Stereo)` endpoint is A2DP and has no microphone. On Android the mic needs
the SCO route (`setCommunicationDevice(BLUETOOTH_SCO)` on API 31+, `startBluetoothSco()`
before that); the app does this when a SCO device is chosen.

Shokz prints the same `-38 dB` microphone sensitivity on every model, without a reference
level, so the figure does not separate the models or compare with other brands. The placement
does: the OpenComm2 boom hears articulation directly; the OpenRun temple mics hear jaw noise
and wind.

| Model | Microphone | Sensitivity (spec sheet) | Placement | Other |
|---|---|---|---|---|
| OpenRun (S803), OpenRun Mini (S804), USB-C revision | Two noise-cancelling mics in the transducer housings, no boom | -38 dB ±3 dB (speaker: 105 dB ±3 dB, 20 Hz to 20 kHz, 8.5 Ω ±20%) | Temples, in front of the ears | Bluetooth 5.1 multipoint, IP67, 26 g, 8 h. Reviewers: fine indoors, poor in wind (RTINGS, SoundGuys) |
| OpenRun Pro 2 (S820), Mini (S821) | Two noise-cancelling mics, no boom; Shokz claims speech stays usable cycling at 25 km/h | -38 dB ±1 dB | Temples | Bluetooth 5.3, IP55, about 30 g, 12 h. DualPitch hybrid driver changes the speaker, not the mic |
| OpenComm2 / OpenComm2 UC (C110; 2025 revision C120) | Adjustable boom with dual-mic DSP noise cancelling; rotates up when idle; mute button on the 2025 revision | -38 dB ±3 dB (speaker: 96.5 dB ±3 dB) | Boom at the corner of the mouth, right side on 2025 revision | Bluetooth 5.1 HFP, or the Loop110 USB dongle (UC), which the host sees as a USB audio device. 16 h talk, 8 h listening. Zoom certified. Best Shokz choice for the mic channel |
| OpenSwim Pro (S710) | Dual noise-cancelling mics in Bluetooth mode; none in MP3 mode | not published | Temples | Bluetooth 5.4, IP68 (2 m, 2 h), 32 GB MP3. Bluetooth does not work under water |

## Headsets with motion

| Model | Motion | Where it works | Notes |
|---|---|---|---|
| Apple AirPods Pro 2, AirPods 3/4, AirPods Max, Beats Fit Pro | Core Motion head tracking, about 25 Hz | macOS and iOS only | On Windows and Android these are ordinary HFP headsets: mic yes, motion no |
| Google Pixel Buds Pro / Pro 2 | `Sensor.TYPE_HEAD_TRACKER` (rotation vector + angular velocity, no linear acceleration) | Pixel phones on Android 13+ | The acceleration feature is empty; use rotation and attitude |

## What this means for an experiment

- With a Shokz headset the only live channel on Windows or Android is audio. Use it for the
  reference transcript and session audio, and bring motion in from a macOS recording (replay) or
  from an Android phone with a tracking headset or the phone IMU (UDP forwarding).
- Mark the headset in the session: the recorder writes `headset` and `headsetMotion` into
  `meta.json` when the OS device name is recognized.
- Compare conditions with the same microphone. Switching between the OpenRun temple mics and
  the OpenComm2 boom changes the whisper transcript far more than any decoder setting.
