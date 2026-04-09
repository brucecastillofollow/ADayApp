package org.bruce.aday.ads

import android.os.Build

/**
 * Google Mobile Ads loads large GMS / Dynamite stacks. On emulators that overlaps mic + Vosk and
 * can coincide with Play Services updates (system may kill dependent apps). Real devices still
 * benefit from deferred init so the first voice session is not competing with ad SDK startup.
 */
object AdsEnvironment {

    fun isLikelyEmulator(): Boolean {
        val fp = Build.FINGERPRINT
        if (fp.startsWith("generic") || fp.startsWith("unknown")) return true
        val model = Build.MODEL.lowercase()
        if ("google_sdk" in model || "emulator" in model || "android sdk built for" in model) return true
        val hw = Build.HARDWARE.lowercase()
        if ("goldfish" in hw || "ranchu" in hw) return true
        val prod = Build.PRODUCT.lowercase()
        if ("sdk_google" in prod || "google_sdk" in prod || "emulator" in prod || prod.startsWith("sdk_")) return true
        return false
    }

    /** When false, do not call [com.google.android.gms.ads.MobileAds.initialize] or load ads. */
    fun shouldInitializeMobileAds(): Boolean = !isLikelyEmulator()
}
