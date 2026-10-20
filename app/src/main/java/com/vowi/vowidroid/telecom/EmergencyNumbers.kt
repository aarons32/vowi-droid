package com.vowi.vowidroid.telecom

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Emergency numbers never go through VowiDroid. An IMS emergency call needs an
 * emergency registration and the caller's location, which VowiDroid does not
 * implement, so these numbers go to the phone's own dialer and its cellular
 * emergency path. AOSP Messaging refuses them the same way.
 */
object EmergencyNumbers {
    private const val TAG = "VowiEmergency"

    /** 3GPP TS 22.101: emergency numbers on every device, whatever the SIM or country says. */
    private val ALWAYS = setOf("112", "911")

    /** TS 22.101's numbers for a device without a SIM; used only when Android cannot be asked. */
    private val FALLBACK = ALWAYS + setOf("000", "08", "110", "999", "118", "119")

    /** True when [number] is an emergency number where the phone is now. */
    fun isEmergency(context: Context, number: String): Boolean {
        val dialable = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (dialable.isEmpty()) return false
        if (dialable in ALWAYS) return true
        val system = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(dialable)
            } else {
                @Suppress("DEPRECATION")
                PhoneNumberUtils.isEmergencyNumber(dialable)
            }
        } catch (error: RuntimeException) {
            // The telephony process is unavailable, or the device cannot call at all.
            Log.w(TAG, "emergency number check failed: ${error.message}")
            null
        }
        return system ?: (dialable in FALLBACK)
    }

    /** Opens the phone's own dialer with [number] filled in, ready for the user to call. */
    fun openSystemDialer(context: Context, number: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val telecom = context.getSystemService(TelecomManager::class.java)
        // VowiDroid also answers ACTION_DIAL, so name the phone's dialer explicitly.
        val dialer = telecom?.defaultDialerPackage?.takeIf { it != context.packageName }
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) telecom?.systemDialerPackage else null
        if (dialer != null) intent.setPackage(dialer)
        try {
            context.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "no dialer for ${intent.`package`}: ${error.message}")
            if (intent.`package` != null) runCatching { context.startActivity(intent.setPackage(null)) }
        }
    }
}
