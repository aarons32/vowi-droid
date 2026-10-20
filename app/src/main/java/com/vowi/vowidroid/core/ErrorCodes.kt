package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.ike.AkaProviderException
import com.vowi.vowidroid.core.ike.EapAuthenticationRejectedException
import com.vowi.vowidroid.core.ike.EpdgRejectedException
import com.vowi.vowidroid.core.ike.IkeUnsupportedSuiteException
import com.vowi.vowidroid.core.ike.InvalidKePayloadException
import com.vowi.vowidroid.core.ike.NoProposalChosenException
import com.vowi.vowidroid.core.ike.ResponderAuthRequiredException
import com.vowi.vowidroid.core.ims.ImsException
import com.vowi.vowidroid.core.ims.RegistrationRejectedException
import com.vowi.vowidroid.core.sim.AkaException
import com.vowi.vowidroid.core.sim.AkaMacFailureException
import com.vowi.vowidroid.core.sim.CardChangedException
import com.vowi.vowidroid.core.sim.SimCardException
import com.vowi.vowidroid.core.sip.SecurityAgreementException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** A short code for a failed attempt, as the status banner shows it: the protocol and the number it gave. */
object ErrorCodes {
    private val sipStatus = Regex("\\b([1-6][0-9]{2})\\b")
    private val fatalNotify = Regex("fatal notification (\\d+)")

    fun of(error: Throwable): String {
        for (current in generateSequence(error) { it.cause }) {
            when (current) {
                is EpdgRejectedException -> return "IKE ${current.code}"
                is NoProposalChosenException -> return "IKE 14"
                is InvalidKePayloadException -> return "IKE 17"
                is EapAuthenticationRejectedException -> return "IKE 24"
                is ResponderAuthRequiredException -> return "IKE AUTH"
                is IkeUnsupportedSuiteException -> return "IKE suite"
                is AkaMacFailureException -> return "SIM MAC"
                is AkaProviderException, is AkaException -> return "SIM"
                is CardChangedException, is SimCardException -> return "SIM"
                is SecurityAgreementException -> return "SIP sec-agree"
                is RegistrationRejectedException, is ImsException -> return sipStatus.find(current.message.orEmpty())?.let { "SIP ${it.groupValues[1]}" } ?: "SIP"
                is SocketTimeoutException -> return "timeout"
                is UnknownHostException -> return "DNS"
            }
            val message = current.message.orEmpty()
            fatalNotify.find(message)?.let { return "IKE ${it.groupValues[1]}" }
            if (message.contains("did not answer") || message.contains("timed out")) return "timeout"
            if (message.startsWith("No ") && message.endsWith("is connected")) return "no network"
        }
        return ""
    }
}
