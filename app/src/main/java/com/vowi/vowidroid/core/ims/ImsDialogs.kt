package com.vowi.vowidroid.core.ims

import com.vowi.vowidroid.core.carrier.CarrierProfiles
import com.vowi.vowidroid.core.sip.SipBuilder
import com.vowi.vowidroid.core.sip.SipMessage
import com.vowi.vowidroid.core.util.Rng
import com.vowi.vowidroid.core.util.toHex

/** The MMTel ICSI (3GPP TS 24.173) in its URN and feature-tag forms. */
const val MMTEL_SERVICE_URN = "urn:urn-7:3gpp-service.ims.icsi.mmtel"
const val MMTEL_FEATURE_TAG = "urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel"

/** The Contact for MMTel dialogs: calls, USSD and subscriptions. */
fun ImsSession.mmtelContact(): String {
    if (profile.registerOptions.contactFormat == CarrierProfiles.CONTACT_GSMA) {
        return "<sip:$contactAddress>;+g.3gpp.icsi-ref=\"$MMTEL_FEATURE_TAG\";+sip.instance=\"<$instanceId>\""
    }
    return "<sip:${identities.user}@$contactAddress;transport=$transport>;+sip.instance=\"<$instanceId>\";audio;+g.3gpp.icsi-ref=\"$MMTEL_FEATURE_TAG\""
}

/**
 * The identity a request goes out under: the number IMS associated with the
 * registration (From, then P-Preferred-Identity), never one guessed from the IMSI.
 */
fun ImsSession.originatingIdentities(): Pair<String, String> {
    val number = ImsSupport.extractAssociatedMsisdn(evidence.pAssociatedUri)?.first
    if (number != null) {
        var from = "sip:$number@${identities.domain}"
        if (profile.imsUserEqPhone) from += ";user=phone"
        return from to "tel:$number"
    }
    return identities.publicIdentity to identities.publicIdentity
}

/** A phone number as a SIP URI in the home domain, for places a tel: URI cannot go (Refer-To headers). */
fun ImsSession.sipUriFor(number: String): String = "sip:$number@${identities.domain};user=phone"

/**
 * A request with our usual headers: the security agreement, P-Access-Network-Info
 * and our User-Agent. Outside a dialog it follows the Service-Route; inside one,
 * pass the dialog's [routes].
 */
fun ImsSession.newRequest(
    method: String,
    target: String,
    to: String,
    callId: String,
    cseq: Long,
    branch: String = Rng.bytes(12).toHex(),
    routes: List<String>? = null,
): SipBuilder {
    val builder = SipBuilder.request(method, target)
        .header("Via", viaHeader(branch))
        .header("Max-Forwards", "70")
    for ((name, value) in securityHeaders()) builder.header(name, value)
    if (routes == null) for ((name, value) in routeHeaders()) builder.header(name, value) else for (route in routes) builder.header("Route", route)
    builder.header("From", "<${originatingIdentities().first}>;tag=$fromTag")
        .header("To", to)
        .header("Call-ID", callId)
        .header("CSeq", "$cseq $method")
    if (accessNetworkInfo.isNotEmpty()) builder.header("P-Access-Network-Info", accessNetworkInfo)
    builder.header("User-Agent", userAgent)
    return builder
}

/** The tag parameter of a From or To header value, or null. */
fun tagOf(header: String): String? =
    header.split(';').drop(1).map { it.trim() }.firstOrNull { it.startsWith("tag=", ignoreCase = true) }?.substringAfter('=')?.trim()

/** The value of a header parameter such as ";isfocus" or ";expires=300". */
fun headerParameter(header: String, name: String): String? {
    val afterUri = header.substringAfter('>', header)
    return afterUri.split(';').map { it.trim() }.firstOrNull { it.substringBefore('=').equals(name, ignoreCase = true) }
        ?.let { if (it.contains('=')) it.substringAfter('=').trim().trim('"') else "" }
}

/** The route set a 2xx gives a dialog: Record-Route, reversed for the UAC (RFC 3261 §12.1.2). */
fun routeSetOf(response: SipMessage): List<String>? {
    val routes = response.values("Record-Route")
    return if (routes.isEmpty()) null else SipMessage.splitHeaderValues(routes).reversed()
}
