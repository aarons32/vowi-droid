package com.vowi.vowidroid

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.se.omapi.Channel
import android.se.omapi.Reader
import android.se.omapi.SEService
import android.util.Log
import androidx.annotation.RequiresApi
import com.vowi.vowidroid.core.sim.AkaChallenge
import com.vowi.vowidroid.core.sim.AkaResult
import com.vowi.vowidroid.core.sim.ApduResponse
import com.vowi.vowidroid.core.sim.CardAccessDeniedException
import com.vowi.vowidroid.core.sim.FoundCard
import com.vowi.vowidroid.core.sim.SimCardException
import com.vowi.vowidroid.core.sim.SimCards
import com.vowi.vowidroid.core.sim.SimIdentity
import com.vowi.vowidroid.core.sim.SimLocation
import com.vowi.vowidroid.core.sim.SimPinRequiredException
import com.vowi.vowidroid.core.sim.Usim
import com.vowi.vowidroid.core.sim.UsimChannel
import com.vowi.vowidroid.core.sim.UsimUnavailableException
import com.vowi.vowidroid.core.util.hexToBytes
import com.vowi.vowidroid.core.util.toHex
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** How the last attempt to reach a slot's card over its own channel went. */
enum class DirectCardOutcome { GRANTED, DENIED, NO_USIM, LOCKED, UNAVAILABLE }

/** SIM slots reached over the card's own channel rather than through Android telephony; see [OmapiSims]. */
interface DirectSims {
    val connected: Boolean
    val outcomes: Map<Int, DirectCardOutcome>
    fun start()
    fun close()

    /** The card in [slot], or null when it could not be read; [label] is Android's name for the SIM, if it has one. */
    fun readCard(slot: Int, label: String): FoundCard?
    fun identity(slot: Int): SimIdentity
    fun authenticate(slot: Int, expectedIccid: String, challenge: AkaChallenge): AkaResult
}

/** What a card needs to know to let VowiDroid in; usable on every Android version. */
object DirectCardAccess {
    private const val FEATURE_OMAPI_UICC = "android.hardware.se.omapi.uicc"

    /** Android 9 brought OMAPI; from Android 10 a phone says whether its SIM slots are on it. */
    fun supported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || context.packageManager.hasSystemFeature(FEATURE_OMAPI_UICC))

    /** The SHA-1 of the app's signing certificate in upper-case hex: what a card's access rules (ARA-M) list. */
    fun certificateHash(context: Context): String {
        val signature = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                @Suppress("DEPRECATION", "PackageManagerGetSignatures")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } ?: return ""
        return MessageDigest.getInstance("SHA-1").digest(signature.toByteArray()).toHex().uppercase()
    }
}

/**
 * The phone's SIM slots over Android's Open Mobile API, the way eSIM manager
 * apps reach a removable eUICC: a logical channel straight to the USIM. That
 * channel keeps working after the SIM is switched off in Android's settings,
 * and needs no adb grant. The card decides who may open it: its access rules
 * (ARA-M) must list the SHA-1 of the app's signing certificate, which
 * [DirectCardAccess.certificateHash] shows.
 *
 * Methods block; call them off the main thread.
 */
@RequiresApi(Build.VERSION_CODES.P)
class OmapiSims(context: Context, private val onChange: () -> Unit) : DirectSims {
    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "vowi-omapi").apply { isDaemon = true } }

    @Volatile
    private var service: SEService? = null
    private val locks = ConcurrentHashMap<Int, Any>()
    private val results = ConcurrentHashMap<Int, DirectCardOutcome>()

    override val connected: Boolean get() = service?.isConnected == true
    override val outcomes: Map<Int, DirectCardOutcome> get() = results.toMap()

    override fun start() {
        if (service != null) return
        service = try {
            SEService(app, executor) {
                Log.i(TAG, "connected to the secure element service")
                onChange()
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "no secure element service: ${error.message}")
            null
        }
    }

    override fun close() {
        runCatching { service?.shutdown() }
        service = null
        results.clear()
    }

    override fun readCard(slot: Int, label: String): FoundCard? {
        val location = SimLocation.PhoneSlot(slot)
        return try {
            withUsim(slot) { card ->
                val iccid = Usim.readIccid(card)
                // A PIN-locked card still gives its ICCID, which says whose it is.
                val identity = try {
                    Usim.readIdentity(card)
                } catch (_: SimPinRequiredException) {
                    null
                }
                results[slot] = if (identity == null) DirectCardOutcome.LOCKED else DirectCardOutcome.GRANTED
                FoundCard(location, identity?.imsi, iccid, label.ifEmpty { identity?.spn.orEmpty() }, issue = if (identity == null) SimCards.ISSUE_PIN else "", direct = true)
            }
        } catch (_: CardAccessDeniedException) {
            results[slot] = DirectCardOutcome.DENIED
            null
        } catch (_: UsimUnavailableException) {
            results[slot] = DirectCardOutcome.NO_USIM
            null
        } catch (error: Exception) {
            results[slot] = DirectCardOutcome.UNAVAILABLE
            Log.w(TAG, "SIM slot ${slot + 1} over OMAPI: ${error.message}")
            null
        }
    }

    override fun identity(slot: Int): SimIdentity = withUsim(slot) { Usim.readIdentity(it) }

    override fun authenticate(slot: Int, expectedIccid: String, challenge: AkaChallenge): AkaResult =
        withUsim(slot) { Usim.authenticate(it, challenge, expectedIccid) }

    /** Opens a channel on the USIM in [slot] for [block] and closes it again, so the modem keeps its channels. */
    private fun <T> withUsim(slot: Int, block: (UsimChannel) -> T): T {
        val service = service?.takeIf { it.isConnected } ?: throw SimCardException("the secure element service is not connected")
        val reader = reader(service, slot) ?: throw SimCardException("this phone has no OMAPI reader for SIM slot ${slot + 1}")
        return synchronized(locks.getOrPut(slot) { Any() }) {
            if (!reader.isSecureElementPresent) throw SimCardException("no card in SIM slot ${slot + 1}")
            val session = try {
                reader.openSession()
            } catch (error: IOException) {
                throw SimCardException("SIM slot ${slot + 1} did not open: ${error.message}", error)
            }
            try {
                val channel = try {
                    session.openLogicalChannel(USIM_AID)
                } catch (_: SecurityException) {
                    throw CardAccessDeniedException()
                } catch (_: NoSuchElementException) {
                    throw UsimUnavailableException()
                } catch (error: IOException) {
                    throw SimCardException("SIM slot ${slot + 1}: ${error.message}", error)
                } catch (error: IllegalStateException) {
                    throw SimCardException("SIM slot ${slot + 1}: ${error.message}", error)
                } catch (error: UnsupportedOperationException) {
                    throw SimCardException("SIM slot ${slot + 1}: ${error.message}", error)
                } ?: throw SimCardException("SIM slot ${slot + 1} has no free logical channel")
                try {
                    block(OmapiCard(channel))
                } finally {
                    runCatching { channel.close() }
                }
            } finally {
                runCatching { session.close() }
            }
        }
    }

    private fun reader(service: SEService, slot: Int): Reader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                service.getUiccReader(slot + 1)
            } catch (_: IllegalArgumentException) {
                null
            }
        } else {
            service.readers.firstOrNull { it.name == "SIM${slot + 1}" || (slot == 0 && it.name == "SIM") }
        }

    /** The USIM over an OMAPI channel: APDUs go through as they are, and Android fills in the channel number. */
    private class OmapiCard(private val channel: Channel) : UsimChannel {
        override fun transmit(command: ByteArray): ApduResponse {
            var response = send(command)
            // Some eUICC and modem pairs answer 6601 (checksum error) now and then; eSIM managers retry it too.
            var tries = 0
            while (response.sw == 0x6601 && tries++ < 10) response = send(command)
            return response
        }

        private fun send(command: ByteArray): ApduResponse {
            val raw = try {
                channel.transmit(command)
            } catch (error: IOException) {
                throw SimCardException("the card did not answer: ${error.message}", error)
            } catch (error: IllegalStateException) {
                throw SimCardException("the channel to the card is closed", error)
            } catch (error: SecurityException) {
                throw SimCardException("Android refused the command: ${error.message}", error)
            }
            if (raw.size < 2) throw SimCardException("the card's answer has no status word")
            val sw = ((raw[raw.size - 2].toInt() and 0xff) shl 8) or (raw[raw.size - 1].toInt() and 0xff)
            return ApduResponse(raw.copyOf(raw.size - 2), sw)
        }
    }

    companion object {
        private const val TAG = "VowiOmapi"

        /** The USIM's AID prefix (TS 31.102 §4): a partial DF name that selects the card's first USIM. */
        private val USIM_AID = "A0000000871002".hexToBytes()
    }
}
