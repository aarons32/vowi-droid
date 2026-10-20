package com.vowi.vowidroid

import android.content.Context
import android.util.Base64
import com.google.gson.JsonParser
import com.vowi.vowidroid.core.carrier.CarrierProfile
import com.vowi.vowidroid.core.carrier.CarrierProfiles
import com.vowi.vowidroid.core.entitlement.HttpCall
import com.vowi.vowidroid.core.entitlement.UrlConnectionTransport
import com.vowi.vowidroid.core.util.CoreLog
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

/**
 * Fresh IMSI-encryption certificates from the carriers' update URLs, kept
 * per carrier profile. The certificate in the profile is the fallback; a
 * fetched one replaces it for the ePDG sign-in. Carriers serve them as PEM,
 * DER, or Android's carrier-keys JSON.
 */
object ImsiCertificates {
    private const val TAG = "ImsiKeys"
    private const val PREFERENCES = "imsi_certificates"
    private const val REFRESH_MS = 7 * 24 * 60 * 60_000L

    /** Lets the carrier profiles use a fetched certificate when there is one. */
    fun install(context: Context) {
        val app = context.applicationContext
        CarrierProfiles.identityCertificateOverride = { id -> certificate(app, id) }
    }

    fun certificate(context: Context, profileId: String): ByteArray? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString("cert.$profileId", null)?.let {
            runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()?.takeIf { bytes -> bytes.isNotEmpty() }
        }

    /** Fetches the profile's certificate when it is due; network I/O, one try a week. */
    fun refreshIfDue(context: Context, profile: CarrierProfile) {
        val url = profile.privacy.updateUrl
        if (url.isEmpty() || !profile.privacy.encryptedEap) return
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val last = preferences.getLong("at.${profile.id}", 0)
        val now = System.currentTimeMillis()
        if (now - last < REFRESH_MS) return
        preferences.edit().putLong("at.${profile.id}", now).apply()
        try {
            val reply = UrlConnectionTransport().exchange(HttpCall("GET", url, listOf("Accept" to "application/json, application/x-pem-file, application/pkix-cert, */*")))
            if (reply.status != 200) {
                CoreLog.w(TAG, "${profile.id}: the identity certificate server answered HTTP ${reply.status}")
                return
            }
            val der = parse(reply.body) ?: run {
                CoreLog.w(TAG, "${profile.id}: the identity certificate server sent nothing usable")
                return
            }
            preferences.edit().putString("cert.${profile.id}", Base64.encodeToString(der, Base64.NO_WRAP)).apply()
            CoreLog.i(TAG, "${profile.id}: identity certificate refreshed")
        } catch (error: Exception) {
            CoreLog.w(TAG, "${profile.id}: the identity certificate could not be fetched: ${error.message}")
        }
    }

    /** The first X.509 certificate with an RSA key in a PEM file, a DER file, or Android's carrier-keys JSON, as DER. */
    fun parse(body: ByteArray): ByteArray? {
        val candidates = ArrayList<ByteArray>()
        val text = String(body, Charsets.ISO_8859_1).trim()
        when {
            text.startsWith("{") -> runCatching {
                val root = JsonParser.parseString(text).asJsonObject
                for (entry in root.getAsJsonArray("carrier-keys")) {
                    val value = entry.asJsonObject.get("certificate")?.asString ?: entry.asJsonObject.get("public-key")?.asString ?: continue
                    candidates += decodeCertificateText(value)
                }
            }
            text.contains("-----BEGIN CERTIFICATE-----") -> candidates += decodeCertificateText(text)
            body.isNotEmpty() && body[0] == 0x30.toByte() -> candidates += body
            else -> runCatching { candidates += Base64.decode(text, Base64.DEFAULT) }
        }
        for (candidate in candidates) {
            val certificate = runCatching {
                CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(candidate)) as X509Certificate
            }.getOrNull() ?: continue
            if (certificate.publicKey is RSAPublicKey) return certificate.encoded
        }
        return null
    }

    private fun decodeCertificateText(value: String): ByteArray {
        val inner = value.substringAfter("-----BEGIN CERTIFICATE-----", value).substringBefore("-----END CERTIFICATE-----")
        return Base64.decode(inner.filter { !it.isWhitespace() }, Base64.DEFAULT)
    }
}
