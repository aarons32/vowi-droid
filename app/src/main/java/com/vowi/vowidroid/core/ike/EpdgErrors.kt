package com.vowi.vowidroid.core.ike

import com.vowi.vowidroid.core.util.u8

/**
 * The ePDG's private error notifications (3GPP TS 24.302 Table 8.1.2.2-1,
 * the same values Android's DataFailCause uses) and what the UE does about
 * them (§7.2.2.2): some mean the subscriber is refused and retrying the
 * ePDG is pointless until the SIM is taken out, others ask for a pause.
 * Carriers list the same split in their phone settings.
 */
object EpdgErrors {
    enum class Policy {
        /** Do not retry this ePDG until the card is re-inserted or the user asks. */
        FATAL,

        /** Try again after a pause. */
        BACK_OFF,
    }

    class Reason(val code: Int, val name: String, val policy: Policy, val explanation: String)

    private val reasons: Map<Int, Reason> = listOf(
        Reason(8192, "PDN_CONNECTION_REJECTION", Policy.FATAL, "your carrier rejected the Wi-Fi calling connection for this SIM"),
        Reason(8193, "MAX_CONNECTION_REACHED", Policy.BACK_OFF, "your carrier allows no more Wi-Fi calling connections for this SIM right now"),
        Reason(9000, "NON_3GPP_ACCESS_TO_EPC_NOT_ALLOWED", Policy.FATAL, "your carrier does not allow Wi-Fi calling for this SIM"),
        Reason(9001, "USER_UNKNOWN", Policy.FATAL, "your carrier does not know this SIM"),
        Reason(9002, "NO_APN_SUBSCRIPTION", Policy.FATAL, "this SIM's plan has no IMS access"),
        Reason(9003, "AUTHORIZATION_REJECTED", Policy.FATAL, "your carrier refused this SIM"),
        Reason(9006, "ILLEGAL_ME", Policy.FATAL, "your carrier refused this device"),
        Reason(10500, "NETWORK_FAILURE", Policy.BACK_OFF, "your carrier's network failed; it asks for a pause"),
        Reason(11001, "RAT_TYPE_NOT_ALLOWED", Policy.FATAL, "your carrier does not allow Wi-Fi access for this SIM"),
        Reason(11002, "IMEI_NOT_ACCEPTED", Policy.FATAL, "your carrier does not accept this device"),
        Reason(11005, "PLMN_NOT_ALLOWED", Policy.FATAL, "your carrier does not allow this network"),
        Reason(11011, "PLMN_NOT_ALLOWED", Policy.FATAL, "your carrier does not allow Wi-Fi calling from this network"),
        Reason(11012, "UNAUTHORIZED_LOCATION", Policy.BACK_OFF, "your carrier does not allow Wi-Fi calling from where you are"),
    ).associateBy { it.code }

    /** The 3GPP BACKOFF_TIMER notification that may come with NETWORK_FAILURE. */
    const val NOTIFY_BACKOFF_TIMER = 41041

    fun reason(code: Int): Reason? = reasons[code]

    /**
     * The seconds a BACKOFF_TIMER notification asks for, or null. Its value is
     * a GPRS timer 3 (TS 24.008 §10.5.7.4a): three unit bits over five
     * value bits; unit 7 means the timer is off.
     */
    fun backoffSeconds(data: ByteArray): Long? {
        if (data.size < 2 || data.u8(0) < 1) return null
        val octet = data.u8(1)
        val value = (octet and 0x1f).toLong()
        return when (octet ushr 5) {
            0 -> value * 10 * 60
            1 -> value * 60 * 60
            2 -> value * 10 * 60 * 60
            3 -> value * 2
            4 -> value * 30
            5 -> value * 60
            6 -> value * 320 * 60 * 60
            else -> null
        }
    }
}

/** The ePDG refused the subscriber with a 3GPP reason; [policy] says whether retrying makes sense. */
class EpdgRejectedException(
    val code: Int,
    val name: String,
    val policy: EpdgErrors.Policy,
    /** The pause the network asked for, in seconds, when it named one. */
    val backoffSeconds: Long? = null,
    explanation: String,
) : IkeException("ike: the ePDG reported $name ($code): $explanation")
