package dev.subvox.core

/**
 * What a headset can contribute to the experiment. Shokz bone-conduction headsets carry no
 * inertial sensor that any host API exposes, so on Shokz the motion channel is empty on every
 * platform and only the microphone channel carries data.
 */
enum class MotionCapability(val label: String) {
    /** Core Motion head tracking (AirPods Pro, AirPods 3/4, AirPods Max, Beats Fit Pro). macOS/iOS only. */
    APPLE_HEADPHONE_MOTION("Apple headphone motion (macOS only)"),
    /** Android `Sensor.TYPE_HEAD_TRACKER`, exposed by spatial-audio headsets on Android 13+. */
    ANDROID_HEAD_TRACKER("Android head-tracker sensor"),
    /** No inertial sensor exposed to the host. */
    NONE("No motion sensor"),
}

/** How the host sees the headset's microphone. */
data class MicProfile(
    /** Marketing description from the maker's spec sheet. */
    val description: String,
    /** Sensitivity as printed on the spec sheet. Shokz prints no reference level, so it is not comparable across brands. */
    val sensitivity: String?,
    /** Where the capsules sit; decides how much jaw and wind the mic hears. */
    val placement: String,
    /** Bluetooth profile the mic runs over. HFP limits the capture to 16 kHz (mSBC) or 8 kHz (CVSD). */
    val transport: String = "Bluetooth Hands-Free Profile (HFP), mono, 16 kHz wideband (mSBC) or 8 kHz (CVSD)",
    val notes: List<String> = emptyList(),
)

data class HeadsetProfile(
    val name: String,
    val maker: String,
    val motion: MotionCapability,
    val mic: MicProfile,
    /** Lower-cased substrings matched against the OS device name. */
    val nameHints: List<String>,
    /** Water rating and other facts that matter for an experiment protocol. */
    val facts: List<String> = emptyList(),
)

/**
 * Catalog of headsets this project has been run or planned with. Specs are from the makers'
 * published sheets as of late 2026; none were measured here.
 */
object Headsets {
    private val shokzNotes = listOf(
        "Qualcomm CVC noise reduction runs on the headset; the host receives the processed mono signal.",
        "While the mic is open the headset is in the HFP profile, so playback drops to the narrowband call codec too.",
        "On Windows the mic is the endpoint named 'Headset (<model> Hands-Free AG Audio)'. The 'Headphones (<model> Stereo)' endpoint is A2DP and has no microphone.",
        "On Android the mic needs the SCO route: setCommunicationDevice(BLUETOOTH_SCO) on API 31+, startBluetoothSco() before that.",
        "No inertial sensor is exposed, so the motion channel is empty with this headset.",
    )

    val shokzOpenRun = HeadsetProfile(
        name = "Shokz OpenRun (S803, OpenRun Mini S804, USB-C revision)",
        maker = "Shokz",
        motion = MotionCapability.NONE,
        mic = MicProfile(
            description = "Two noise-cancelling microphones built into the transducer housings; no boom.",
            sensitivity = "-38 dB +/- 3 dB (speaker side: 105 dB +/- 3 dB, 20 Hz to 20 kHz, 8.5 ohm +/- 20%)",
            placement = "At the temples, in front of the ears. Picks up jaw movement noise; weak in wind and traffic.",
            notes = shokzNotes + "Reviewers rate call pickup acceptable indoors and poor outdoors (RTINGS, SoundGuys).",
        ),
        nameHints = listOf("openrun", "shokz openrun", "aftershokz openrun"),
        facts = listOf("Bluetooth 5.1, multipoint", "IP67", "26 g", "8 h playback"),
    )

    val shokzOpenRunPro2 = HeadsetProfile(
        name = "Shokz OpenRun Pro 2 (S820, Mini S821)",
        maker = "Shokz",
        motion = MotionCapability.NONE,
        mic = MicProfile(
            description = "Two noise-cancelling microphones in the transducer housings; no boom. Shokz claims wind resistance for speech while cycling at 25 km/h.",
            sensitivity = "-38 dB +/- 1 dB",
            placement = "At the temples, in front of the ears.",
            notes = shokzNotes + "DualPitch hybrid driver adds a small air-conduction speaker; the mic path is unchanged from OpenRun.",
        ),
        nameHints = listOf("openrun pro 2", "openrun pro2", "openrunpro2"),
        facts = listOf("Bluetooth 5.3, multipoint", "IP55", "about 30 g", "12 h playback"),
    )

    val shokzOpenComm2 = HeadsetProfile(
        name = "Shokz OpenComm2 / OpenComm2 UC (C110, 2025 revision C120)",
        maker = "Shokz",
        motion = MotionCapability.NONE,
        mic = MicProfile(
            description = "Adjustable boom microphone with dual-mic DSP noise cancelling; rotates up when not in use. Dedicated mute button on the 2025 revision.",
            sensitivity = "-38 dB +/- 3 dB (speaker side: 96.5 dB +/- 3 dB, 20 Hz to 20 kHz)",
            placement = "Boom at the corner of the mouth, right side on the 2025 revision, left on the original.",
            transport = "Bluetooth 5.1 HFP (SBC for media); or the Loop110 USB dongle (UC version), which the host sees as a USB audio device, about 30 m range",
            notes = shokzNotes + listOf(
                "Best Shokz choice for the microphone channel: the boom hears articulation directly and the dongle sidesteps HFP routing on Windows.",
                "16 h talk time, 8 h listening, 14 days standby; 5-minute charge gives 2 h.",
                "Zoom certified; no Microsoft Teams certification found.",
            ),
        ),
        nameHints = listOf("opencomm", "loop110", "loop 110", "shokz loop"),
        facts = listOf("IP55", "35 g", "Bluetooth 5.1, multipoint"),
    )

    val shokzOpenSwimPro = HeadsetProfile(
        name = "Shokz OpenSwim Pro (S710)",
        maker = "Shokz",
        motion = MotionCapability.NONE,
        mic = MicProfile(
            description = "Dual noise-cancelling microphones for calls in Bluetooth mode; none in MP3 mode.",
            sensitivity = null,
            placement = "At the temples.",
            notes = shokzNotes + "Bluetooth does not work under water; the MP3 mode has no microphone path.",
        ),
        nameHints = listOf("openswim pro", "openswimpro"),
        facts = listOf("Bluetooth 5.4", "IP68, 2 m for 2 h", "about 27 g", "9 h playback", "32 GB MP3"),
    )

    val airPodsPro = HeadsetProfile(
        name = "Apple AirPods Pro (2nd generation)",
        maker = "Apple",
        motion = MotionCapability.APPLE_HEADPHONE_MOTION,
        mic = MicProfile(
            description = "Dual beamforming microphones plus an inward-facing microphone; the inward mic and the speech-detecting accelerometer are not exposed to apps.",
            sensitivity = null,
            placement = "Stem tips, below the ear.",
            notes = listOf(
                "Head motion arrives at about 25 Hz through Core Motion on macOS/iOS only.",
                "On Windows and Android the AirPods are an ordinary HFP headset: mic yes, motion no.",
            ),
        ),
        nameHints = listOf("airpods"),
    )

    val pixelBudsPro = HeadsetProfile(
        name = "Google Pixel Buds Pro / Pro 2",
        maker = "Google",
        motion = MotionCapability.ANDROID_HEAD_TRACKER,
        mic = MicProfile(
            description = "Three microphones per bud with wind-blocking mesh; voice accelerometer not exposed.",
            sensitivity = null,
            placement = "In the ear, outward-facing.",
            notes = listOf(
                "Spatial audio head tracking is exposed on Pixel phones running Android 13+ as Sensor.TYPE_HEAD_TRACKER: rotation and angular velocity, no linear acceleration.",
            ),
        ),
        nameHints = listOf("pixel buds pro"),
    )

    val all: List<HeadsetProfile> = listOf(
        shokzOpenRunPro2, shokzOpenComm2, shokzOpenSwimPro, shokzOpenRun, airPodsPro, pixelBudsPro,
    )

    /**
     * Matches an OS-reported device name such as "Headset (OpenRun Pro 2 Hands-Free AG Audio)".
     * Longer hints are checked first so "openrun pro 2" wins over "openrun".
     */
    fun match(deviceName: String?): HeadsetProfile? {
        val n = deviceName?.lowercase() ?: return null
        return all.flatMap { p -> p.nameHints.map { it to p } }
            .sortedByDescending { it.first.length }
            .firstOrNull { n.contains(it.first) }
            ?.second
    }

    /** True when the OS name is the HFP (microphone-capable) endpoint of a Bluetooth headset on Windows. */
    fun isWindowsHandsFreeEndpoint(deviceName: String): Boolean =
        deviceName.contains("Hands-Free", ignoreCase = true)

    /** True when the OS name is the A2DP (playback-only) endpoint on Windows. */
    fun isWindowsStereoEndpoint(deviceName: String): Boolean =
        deviceName.contains("Stereo", ignoreCase = true) && !isWindowsHandsFreeEndpoint(deviceName)
}
