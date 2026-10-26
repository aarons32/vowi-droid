package com.vowi.vowidroid

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import com.vowi.vowidroid.core.sim.AkaChallenge
import com.vowi.vowidroid.core.sim.AkaException
import com.vowi.vowidroid.core.sim.AkaMacFailureException
import com.vowi.vowidroid.core.sim.AkaProvider
import com.vowi.vowidroid.core.sim.AkaResult
import com.vowi.vowidroid.core.sim.SimIdentity
import com.vowi.vowidroid.core.util.concat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * The phone's own SIM cards. Only two things are asked of Android: the
 * card's identity and the USIM's answer to an authentication challenge.
 * VowiDroid's own stack does the tunnel, the registration and SIP.
 *
 * Methods block; call them off the main thread.
 */
class PhoneSims(context: Context) {
    private val app = context.applicationContext
    private val telephony = app.getSystemService(TelephonyManager::class.java)
    private val subscriptions = app.getSystemService(SubscriptionManager::class.java)

    /** What is in a slot: nothing, a card the phone has switched off or not brought up, a locked card, or a card in use. */
    enum class SlotState { ABSENT, OFF, LOCKED, READY }

    data class Slot(val slot: Int, val state: SlotState, val subscriptionId: Int, val label: String, val accessIssue: String?) {
        /** A card is physically in the slot. */
        val present: Boolean get() = state != SlotState.ABSENT
    }

    fun slots(): List<Slot> {
        val issue = SimAccess.issue(app)
        return (0 until slotCount()).map { slot ->
            val state = when (runCatching { telephony.getSimState(slot) }.getOrDefault(TelephonyManager.SIM_STATE_UNKNOWN)) {
                TelephonyManager.SIM_STATE_ABSENT, TelephonyManager.SIM_STATE_UNKNOWN -> SlotState.ABSENT
                TelephonyManager.SIM_STATE_READY -> SlotState.READY
                TelephonyManager.SIM_STATE_PIN_REQUIRED, TelephonyManager.SIM_STATE_PUK_REQUIRED, TelephonyManager.SIM_STATE_NETWORK_LOCKED -> SlotState.LOCKED
                else -> SlotState.OFF
            }
            val info = if (SimAccess.hasPhonePermission(app)) subscription(slot) else null
            if (info == null && state != SlotState.ABSENT && !SimAccess.hasPhonePermission(app)) {
                PermissionDenials.report(AppPermissions.Need.PHONE, R.string.denial_sim_list)
            }
            Slot(slot, state, info?.subscriptionId ?: -1, info?.let(::label).orEmpty(), if (state != SlotState.ABSENT) issue else null)
        }
    }

    /** What names a card: the subscriber, the card, and Android's subscription for it. */
    data class CardIds(val imsi: String, val iccid: String, val subscriptionId: Int)

    /**
     * The identifiers of the card in [slot], or null when there is none or
     * it cannot be read now. With [fallbackSubscriptionId], a card the phone
     * no longer lists, e.g. one it switched off in airplane mode, is still
     * asked through the subscription it had.
     */
    @SuppressLint("HardwareIds", "MissingPermission")
    fun cardIds(slot: Int, fallbackSubscriptionId: Int = -1): CardIds? {
        if (SimAccess.issue(app) != null) return null
        val (phone, info, subscriptionId) = runCatching { target(slot, fallbackSubscriptionId) }.getOrNull() ?: return null
        val imsi = runCatching { phone.subscriberId?.trim().orEmpty() }.getOrDefault("")
        val iccid = if (imsi.isEmpty()) "" else runCatching { iccid(phone, info, slot) }.getOrDefault("")
        if (imsi.isEmpty() || iccid.isEmpty()) return null
        return CardIds(imsi, iccid, subscriptionId)
    }

    /** The SIM's identity for the protocol stack. Fails with a plain message when access is missing. */
    @SuppressLint("HardwareIds", "MissingPermission") // granted through SimAccess
    fun identity(slot: Int, fallbackSubscriptionId: Int = -1): SimIdentity {
        val (phone, info, subscriptionId) = target(slot, fallbackSubscriptionId)
        val imsi = phone.subscriberId?.trim().orEmpty()
        check(imsi.isNotEmpty()) { "SIM ${slot + 1} did not report its IMSI" }
        val iccid = iccid(phone, info, slot)
        check(iccid.isNotEmpty()) { "SIM ${slot + 1} did not report its ICCID" }
        val operator = phone.simOperator.orEmpty().trim()
        check(operator.length in 5..6 && operator.all { it.isDigit() }) { "SIM ${slot + 1} did not report its home network" }
        return SimIdentity(
            iccid = iccid,
            imsi = imsi,
            homeMcc = operator.substring(0, 3),
            homeMnc = operator.substring(3),
            spn = phone.simOperatorName.orEmpty().trim(),
            // MVNOs sharing a network code are told apart by GID1.
            gid1 = runCatching { phone.groupIdLevel1 }.getOrNull().orEmpty().trim(),
            smsc = smsCenter(subscriptionId),
        )
    }

    /** The phone's own IMEI for [slot], or "" when Android will not give it; the SIM-access grant covers device identifiers. */
    @SuppressLint("HardwareIds", "MissingPermission")
    fun imei(slot: Int): String = runCatching { telephony.getImei(slot)?.trim().orEmpty() }.getOrDefault("")

    /** Android sees no card at all in [slot], rather than one it cannot use right now. */
    fun slotEmpty(slot: Int): Boolean = runCatching { telephony.getSimState(slot) == TelephonyManager.SIM_STATE_ABSENT }.getOrDefault(false)

    /** In airplane mode some phones switch their SIMs off, so the cards look gone. */
    fun airplaneMode(): Boolean = Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    @SuppressLint("MissingPermission")
    fun authenticate(slot: Int, fallbackSubscriptionId: Int, challenge: AkaChallenge): AkaResult {
        val (phone, _, _) = target(slot, fallbackSubscriptionId)
        // RAND/AUTN go to the USIM's AUTHENTICATE command in the 3G context;
        // the card's response comes back unchanged (tag DB or DC).
        val input = concat(byteArrayOf(16), challenge.rand, byteArrayOf(16), challenge.autn)
        val response = try {
            phone.getIccAuthentication(TelephonyManager.APPTYPE_USIM, TelephonyManager.AUTHTYPE_EAP_AKA, Base64.encodeToString(input, Base64.NO_WRAP))
        } catch (error: SecurityException) {
            PermissionDenials.report(AppPermissions.Need.SIM_ACCESS, R.string.denial_sim_auth)
            throw AkaException("SIM access is not granted: ${error.message}", error)
        } catch (error: RuntimeException) {
            throw AkaException("the SIM could not be asked to authenticate: ${error.message}", error)
        }
        if (response == null) {
            // Android returns nothing both for a card that rejected the network and for a missing grant.
            if (!SimAccess.hasSimAuthentication(app)) {
                PermissionDenials.report(AppPermissions.Need.SIM_ACCESS, R.string.denial_sim_auth)
                throw AkaException("SIM access is not granted")
            }
            throw AkaMacFailureException()
        }
        return AkaResult.fromUiccResponse(Base64.decode(response, Base64.DEFAULT))
    }

    /** Android has two ICCID reads with different access rules; use whichever answers. */
    @SuppressLint("HardwareIds", "MissingPermission")
    private fun iccid(phone: TelephonyManager, info: SubscriptionInfo?, slot: Int): String {
        @Suppress("DEPRECATION") // still the only per-subscription ICCID read
        val fromTelephony = runCatching { phone.simSerialNumber?.trim().orEmpty() }
        val fromSubscription = info?.iccId?.trim().orEmpty()
        val iccid = fromTelephony.getOrNull().orEmpty().ifEmpty { fromSubscription }.trimEnd('f', 'F')
        if (iccid.isEmpty()) {
            Log.w(TAG, "SIM ${slot + 1}: no ICCID; TelephonyManager ${fromTelephony.exceptionOrNull()?.javaClass?.simpleName ?: "returned nothing"}, SubscriptionInfo returned nothing")
        }
        return iccid
    }

    /** The phone's SIMs VowiDroid can read now, for the setup screen. */
    fun readable(): List<ReadableSim> {
        if (SimAccess.issue(app) != null) return emptyList()
        return (0 until slotCount()).mapNotNull { slot ->
            val identity = runCatching { identity(slot, -1) }.getOrNull() ?: return@mapNotNull null
            ReadableSim(slot, subscription(slot)?.let(::label).orEmpty(), identity.iccid)
        }
    }

    /** Android lets only privileged apps read the SIM's SMS centre; "" otherwise, and the core falls back to the user's entry. */
    @SuppressLint("MissingPermission")
    private fun smsCenter(subscriptionId: Int): String {
        val fromSim = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) smsManager(subscriptionId).smscAddress else null
        } catch (_: RuntimeException) {
            null
        }
        return fromSim?.let { normalizeSmsCenter(it) }.orEmpty()
    }

    @Suppress("DEPRECATION")
    private fun smsManager(subscriptionId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
        } else {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }

    private fun slotCount(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            telephony.activeModemCount
        } else {
            @Suppress("DEPRECATION")
            telephony.phoneCount
        }

    @SuppressLint("MissingPermission")
    private fun subscription(slot: Int): SubscriptionInfo? = subscriptions.getActiveSubscriptionInfoForSimSlotIndex(slot)

    /** The TelephonyManager for the card in [slot]: its active subscription, or the one it had when the phone has switched it off. */
    private fun target(slot: Int, fallbackSubscriptionId: Int): Triple<TelephonyManager, SubscriptionInfo?, Int> {
        if (!SimAccess.hasPhonePermission(app)) {
            PermissionDenials.report(AppPermissions.Need.PHONE, R.string.denial_sim_list)
            error("VowiDroid needs the phone permission to read SIM ${slot + 1}")
        }
        val info = subscription(slot)
        if (info != null) return Triple(telephony.createForSubscriptionId(info.subscriptionId), info, info.subscriptionId)
        if (fallbackSubscriptionId >= 0) return Triple(telephony.createForSubscriptionId(fallbackSubscriptionId), null, fallbackSubscriptionId)
        error("SIM slot ${slot + 1} has no active subscription")
    }

    fun label(slot: Int): String = subscription(slot)?.let(::label).orEmpty()

    /** The voicemail number from the SIM or the carrier configuration, or null. */
    fun voicemailNumber(slot: Int): String? {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            val info = subscription(slot) ?: return null
            telephony.createForSubscriptionId(info.subscriptionId).voiceMailNumber
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun label(info: SubscriptionInfo): String =
        info.displayName?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: info.carrierName?.toString()?.trim().orEmpty()

    /** Calls back whenever Android's subscriptions change. */
    fun watch(onChange: () -> Unit): SubscriptionManager.OnSubscriptionsChangedListener {
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() = onChange()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            subscriptions.addOnSubscriptionsChangedListener(app.mainExecutor, listener)
        } else {
            @Suppress("DEPRECATION")
            subscriptions.addOnSubscriptionsChangedListener(listener)
        }
        return listener
    }

    fun unwatch(listener: SubscriptionManager.OnSubscriptionsChangedListener) {
        subscriptions.removeOnSubscriptionsChangedListener(listener)
    }

    data class ReadableSim(val slot: Int, val label: String, val iccid: String)

    companion object {
        private const val TAG = "VowiSim"
        private const val PREFERENCES = "sms_centers"

        /** Keeps digits and a leading +; returns "" when nothing usable is left. */
        fun normalizeSmsCenter(value: String): String {
            val trimmed = value.trim().trim('"')
            val digits = trimmed.filter(Char::isDigit)
            if (digits.length !in 3..20) return ""
            return (if (trimmed.startsWith("+")) "+" else "") + digits
        }

        fun savedSmsCenter(context: Context, iccid: String): String =
            if (iccid.isEmpty()) "" else context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(iccid, "").orEmpty()

        fun saveSmsCenter(context: Context, iccid: String, value: String) {
            if (iccid.isEmpty()) return
            val normalized = normalizeSmsCenter(value)
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().apply {
                if (normalized.isEmpty()) remove(iccid) else putString(iccid, normalized)
            }.apply()
        }
    }
}
