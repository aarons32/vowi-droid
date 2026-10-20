package com.vowi.vowidroid.core.ike

import com.vowi.vowidroid.core.carrier.CarrierProfile
import com.vowi.vowidroid.core.sim.SimIdentity
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * The names one line presents to the ePDG: [idi] in IKE_AUTH, [eap] in
 * EAP-Response/Identity and AT_IDENTITY, and the [keyCandidates] the
 * EAP-AKA keys may be derived from. The server's MAC on its challenge tells
 * which candidate the server used, so no guess reaches the network.
 */
class EapIdentity(
    val idi: ByteArray,
    val eap: ByteArray,
    val keyCandidates: List<Pair<String, ByteArray>>,
    /** "permanent", "anonymous" or "anonymous+encrypted", for the log. */
    val mode: String,
)

/**
 * IMSI privacy on the ePDG path, as iPhones do it for carriers that ask
 * (Apple patent US10833876B2, the WBA IMSI Privacy Protection for Wi-Fi):
 * the IKE identity is `anonymous@realm`, and the EAP identity carries a NUL
 * byte, then the permanent username `0<IMSI>` encrypted with the carrier's
 * RSA key (OAEP, SHA-256) in base64, then `,<key identifier>` when the
 * carrier names its key.
 */
object EapIdentities {
    /** The permanent NAI in the clear, as before privacy existed. */
    fun plain(identity: SimIdentity, profile: CarrierProfile): EapIdentity {
        val permanent = EapAka.permanentIdentity(identity, profile)
        return EapIdentity(permanent, permanent, listOf("permanent" to permanent), "permanent")
    }

    /** The NAI realm: the carrier's own when it names one, else the 3GPP one from the SIM's PLMN. */
    fun realm(identity: SimIdentity, profile: CarrierProfile): String {
        profile.privacy.realm.takeIf { it.isNotEmpty() }?.let { return it }
        return String(EapAka.permanentIdentity(identity, profile), Charsets.US_ASCII).substringAfter('@')
    }

    /** What the profile asks for; [plainOnly] falls back to the clear identity, e.g. after the ePDG refused the protected one. */
    fun forProfile(identity: SimIdentity, profile: CarrierProfile, plainOnly: Boolean = false): EapIdentity {
        val privacy = profile.privacy
        if (plainOnly || (!privacy.anonymousIdi && !privacy.canEncrypt)) return plain(identity, profile)
        val permanent = EapAka.permanentIdentity(identity, profile)
        val anonymous = "${privacy.anonymousUsername.ifEmpty { "anonymous" }}@${realm(identity, profile)}".toByteArray(Charsets.US_ASCII)
        if (!privacy.canEncrypt) {
            // Anonymous IKE identity, permanent EAP identity: what Verizon's settings ask for.
            return EapIdentity(anonymous, permanent, listOf("permanent" to permanent, "anonymous" to anonymous), "anonymous")
        }
        val username = String(permanent, Charsets.US_ASCII).substringBefore('@').toByteArray(Charsets.US_ASCII)
        val encrypted = encrypt(username, privacy.certificateDer!!, privacy.keyIdentifier)
        val candidates = listOf("permanent" to permanent, "encrypted" to encrypted, "anonymous" to anonymous, "username" to username)
        return EapIdentity(anonymous, encrypted, candidates, "anonymous+encrypted")
    }

    /** NUL, base64(RSA-OAEP-SHA256(username)), and `,keyIdentifier` when given. */
    fun encrypt(username: ByteArray, certificateDer: ByteArray, keyIdentifier: String): ByteArray {
        val certificate = try {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificateDer)) as X509Certificate
        } catch (error: Exception) {
            throw IkeException("ike: the carrier's identity encryption certificate is invalid: ${error.message}", error)
        }
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, certificate.publicKey, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        val ciphertext = try {
            cipher.doFinal(username)
        } catch (error: Exception) {
            throw IkeException("ike: the identity could not be encrypted: ${error.message}", error)
        }
        val suffix = keyIdentifier.trim().takeIf { it.isNotEmpty() }?.let { ",$it" }.orEmpty()
        return ("\u0000" + Base64.getEncoder().encodeToString(ciphertext) + suffix).toByteArray(Charsets.US_ASCII)
    }
}
