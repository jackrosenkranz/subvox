package dev.subvox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadsetsTest {
    @Test
    fun windowsEndpointNamesResolveToTheRightShokzModel() {
        assertEquals(Headsets.shokzOpenRun, Headsets.match("Headset (OpenRun Hands-Free AG Audio)"))
        assertEquals(Headsets.shokzOpenRunPro2, Headsets.match("Headset (OpenRun Pro 2 Hands-Free AG Audio)"))
        // Exact label the Android app showed on a phone paired with an OpenRun Pro 2.
        assertEquals(Headsets.shokzOpenRunPro2, Headsets.match("OpenRun Pro 2 by Shokz (Bluetooth headset mic)"))
        assertEquals(Headsets.shokzOpenComm2, Headsets.match("Headset (OpenComm2 UC by Shokz Hands-Free AG Audio)"))
        assertEquals(Headsets.shokzOpenComm2, Headsets.match("Microphone (Shokz Loop110)"))
        assertEquals(Headsets.airPodsPro, Headsets.match("Headset (Jack's AirPods Pro Hands-Free AG Audio)"))
        assertNull(Headsets.match("Microphone Array (Realtek(R) Audio)"))
        assertNull(Headsets.match(null))
    }

    @Test
    fun everyShokzProfileHasNoMotionAndAnHfpMic() {
        val shokz = Headsets.all.filter { it.maker == "Shokz" }
        assertEquals(4, shokz.size)
        for (p in shokz) {
            assertEquals(MotionCapability.NONE, p.motion, p.name)
            assertTrue(p.mic.notes.any { it.contains("HFP") }, p.name)
        }
        assertEquals("-38 dB +/- 3 dB (speaker side: 105 dB +/- 3 dB, 20 Hz to 20 kHz, 8.5 ohm +/- 20%)", Headsets.shokzOpenRun.mic.sensitivity)
    }

    @Test
    fun windowsEndpointKinds() {
        assertTrue(Headsets.isWindowsHandsFreeEndpoint("Headset (OpenRun Hands-Free AG Audio)"))
        assertTrue(Headsets.isWindowsStereoEndpoint("Headphones (OpenRun Stereo)"))
        assertTrue(!Headsets.isWindowsStereoEndpoint("Headset (OpenRun Hands-Free AG Audio)"))
    }
}
