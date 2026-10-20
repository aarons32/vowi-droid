package com.vowi.vowidroid.telecom

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import com.vowi.vowidroid.R
import com.vowi.vowidroid.data.CallSettings
import com.vowi.vowidroid.data.Line
import com.vowi.vowidroid.data.VowiRepository

/**
 * One self-managed Telecom account per VowiDroid line. Android then arbitrates
 * audio focus and Bluetooth/car routing for VowiDroid's calls, while VowiDroid draws
 * every call screen itself. Two extras open the calls up to the rest of the
 * phone: the system call history (when the user wants it), and in-call
 * services that ask for self-managed calls, such as a car or a watch.
 */
object PhoneAccounts {
    private const val TAG = "VowiAccounts"

    fun handle(context: Context, deviceId: String): PhoneAccountHandle =
        PhoneAccountHandle(ComponentName(context, VowiConnectionService::class.java), deviceId)

    fun register(context: Context, deviceId: String, label: String, number: String = "") {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return
        val extras = Bundle()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            extras.putBoolean(PhoneAccount.EXTRA_LOG_SELF_MANAGED_CALLS, CallSettings.logToSystem(context))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            extras.putBoolean(PhoneAccount.EXTRA_ADD_SELF_MANAGED_CALLS_TO_INCALLSERVICE, true)
        }
        val builder = PhoneAccount.builder(handle(context, deviceId), label)
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL, PhoneAccount.SCHEME_SIP))
            .setShortDescription(number.ifEmpty { label })
            .setIcon(Icon.createWithResource(context, R.drawable.ic_notification))
            .setExtras(extras)
        if (number.isNotEmpty()) builder.setAddress(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null))
        try {
            telecom.registerPhoneAccount(builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot register the Telecom account for $deviceId", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Telecom rejected the account for $deviceId", e)
        }
    }

    /** Registers the account for [deviceId] under the line's own name and number. */
    fun ensure(context: Context, deviceId: String, fallbackLabel: String = deviceId) {
        val line = VowiRepository.lines.value.firstOrNull { it.id == deviceId }
        register(context, deviceId, line?.name?.ifBlank { null } ?: fallbackLabel, line?.phoneNumber.orEmpty())
    }

    /** Takes a removed line's account away from Telecom. */
    fun unregister(context: Context, deviceId: String) {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return
        runCatching { telecom.unregisterPhoneAccount(handle(context, deviceId)) }
            .onFailure { Log.w(TAG, "cannot unregister the Telecom account for $deviceId", it) }
    }

    /** Keeps one account per line that can make calls. */
    fun sync(context: Context, lines: List<Line>) {
        lines.filter { it.usable }.forEach { register(context, it.id, it.name, it.phoneNumber) }
    }

    /** Applies a changed setting, e.g. logging to the system call history, to every account. */
    fun refresh(context: Context) = sync(context, VowiRepository.lines.value)
}
