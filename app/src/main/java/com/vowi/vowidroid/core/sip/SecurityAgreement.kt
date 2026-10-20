package com.vowi.vowidroid.core.sip

import com.vowi.vowidroid.core.util.Rng
import com.vowi.vowidroid.core.util.u16
import com.vowi.vowidroid.core.util.u32

class SecurityAgreementException(message: String) : Exception("ims: $message")

/** What the UE offers in Security-Client (3GPP TS 33.203, RFC 3329). */
class SecurityProposal(
    val spiClient: Long,
    val spiServer: Long,
    val portClient: Int,
    val portServer: Int,
    val integrityAlgorithms: List<String> = listOf("hmac-md5-96", "hmac-sha-1-96"),
    val encryptionAlgorithms: List<String> = listOf("null", "des-ede3-cbc", "aes-cbc"),
    /** AT&T's registrar wants a single explicit mechanism. */
    val singleMechanism: Boolean = false,
) {
    fun headerValue(): String {
        if (singleMechanism) {
            return "ipsec-3gpp; alg=${integrityAlgorithms.first()}; ealg=${encryptionAlgorithms.first()}; prot=esp; mod=trans; " +
                "spi-c=$spiClient; spi-s=$spiServer; port-c=$portClient; port-s=$portServer"
        }
        // TS 33.203 writes spi-c and spi-s as ten decimal digits.
        val values = ArrayList<String>()
        var index = 0
        for (integrity in integrityAlgorithms) {
            for (encryption in encryptionAlgorithms) {
                val preference = if (index == 0) "1.000" else "0.%03d".format(999 - index)
                values.add(
                    "ipsec-3gpp;q=$preference;alg=$integrity;prot=esp;mod=trans;ealg=$encryption;" +
                        "spi-c=%010d;spi-s=%010d;port-c=$portClient;port-s=$portServer".format(spiClient, spiServer),
                )
                index++
            }
        }
        return values.joinToString(", ")
    }

    fun supportsIntegrity(value: String) = integrityAlgorithms.any { it.equals(value, ignoreCase = true) }
    fun supportsEncryption(value: String) = encryptionAlgorithms.any { it.equals(value, ignoreCase = true) }

    companion object {
        fun randomSpi(exclude: Long = 0): Long {
            repeat(16) {
                val spi = Rng.bytes(4).u32(0)
                if (spi >= 256 && spi != exclude) return spi
            }
            throw SecurityAgreementException("could not allocate a protected SPI")
        }

        fun validProtectedPort(port: Int): Boolean = port > 1024 && port <= 65535 && port != 5060 && port != 5061

        /** A random protected port the stack accepts. */
        fun randomProtectedPort(exclude: Int, free: (Int) -> Boolean): Int {
            repeat(32) {
                val port = 20000 + Rng.bytes(2).u16(0) % 44000
                if (port != exclude && validProtectedPort(port) && free(port)) return port
            }
            throw SecurityAgreementException("no protected local port is available")
        }
    }
}

/** One mechanism from a Security-Server header. */
class SecurityMechanism(
    val raw: String,
    val name: String,
    val algorithm: String,
    val protocol: String,
    val mode: String,
    val encryption: String,
    val spiClient: Long,
    val spiServer: Long,
    val portClient: Int,
    val portServer: Int,
    val preference: Int,
)

class SecurityAgreement(val selected: SecurityMechanism, val verifyValue: String)

object SecurityAgreements {
    private class IncompleteOffer : Exception()

    /** Picks the P-CSCF's best mechanism that we offered; the Security-Verify value echoes all of them. */
    fun parse(values: List<String>, proposal: SecurityProposal): SecurityAgreement {
        val items = SipMessage.splitHeaderValues(values)
        if (items.isEmpty()) throw SecurityAgreementException("a supported ipsec-3gpp security agreement is required")
        val candidates = ArrayList<SecurityMechanism>()
        for (item in items) {
            val mechanism = try {
                parseMechanism(item)
            } catch (_: IncompleteOffer) {
                continue
            } catch (error: SecurityAgreementException) {
                val name = item.substringBefore(';').trim().lowercase()
                if (name == "ipsec-3gpp") throw SecurityAgreementException("malformed ipsec-3gpp Security-Server: ${error.message}")
                continue
            }
            if (!mechanism.name.equals("ipsec-3gpp", ignoreCase = true) || !proposal.supportsIntegrity(mechanism.algorithm) ||
                !mechanism.protocol.equals("esp", ignoreCase = true) || !mechanism.mode.equals("trans", ignoreCase = true) ||
                !proposal.supportsEncryption(mechanism.encryption)
            ) continue
            if (mechanism.spiClient == 0L || mechanism.spiServer == 0L || mechanism.spiClient == mechanism.spiServer ||
                mechanism.spiClient == proposal.spiClient || mechanism.spiClient == proposal.spiServer ||
                mechanism.spiServer == proposal.spiClient || mechanism.spiServer == proposal.spiServer ||
                !SecurityProposal.validProtectedPort(mechanism.portClient) || !SecurityProposal.validProtectedPort(mechanism.portServer) ||
                mechanism.portClient == mechanism.portServer
            ) continue
            candidates.add(mechanism)
        }
        if (candidates.isEmpty()) throw SecurityAgreementException("a supported ipsec-3gpp security agreement is required")
        val best = candidates.sortedByDescending { it.preference }.first()
        return SecurityAgreement(best, items.joinToString(", "))
    }

    fun parseMechanism(value: String): SecurityMechanism {
        val parts = value.split(';')
        val name = parts[0].trim().lowercase()
        val parameters = HashMap<String, String>()
        for (raw in parts.drop(1)) {
            val equals = raw.indexOf('=')
            if (equals < 0) throw SecurityAgreementException("malformed Security-Server parameter")
            val key = raw.substring(0, equals).trim().lowercase()
            val parameterValue = raw.substring(equals + 1).trim().trim('"')
            if (key.isEmpty() || parameterValue.isEmpty()) throw SecurityAgreementException("empty Security-Server parameter")
            if (parameters.containsKey(key)) throw SecurityAgreementException("duplicate Security-Server parameter")
            parameters[key] = parameterValue
        }
        val keys = listOf("spi-c", "spi-s", "port-c", "port-s")
        val present = keys.count { !parameters[it].isNullOrEmpty() }
        if (present == 0) throw IncompleteOffer()
        if (present != keys.size) throw SecurityAgreementException("partially specified Security-Server SA parameters")
        return SecurityMechanism(
            raw = value.trim(),
            name = name,
            algorithm = parameters["alg"].orEmpty().lowercase(),
            protocol = parameters["prot"]?.lowercase() ?: "esp",
            mode = parameters["mod"]?.lowercase() ?: "trans",
            encryption = parameters["ealg"]?.lowercase() ?: "null",
            spiClient = decimalSpi(parameters["spi-c"]!!),
            spiServer = decimalSpi(parameters["spi-s"]!!),
            portClient = decimalPort(parameters["port-c"]!!),
            portServer = decimalPort(parameters["port-s"]!!),
            preference = preference(parameters["q"].orEmpty()),
        )
    }

    private fun decimalSpi(value: String): Long {
        if (value.isEmpty() || value.length > 10) throw SecurityAgreementException("invalid Security-Server SPI")
        val parsed = value.toLongOrNull() ?: throw SecurityAgreementException("invalid Security-Server SPI")
        if (parsed < 0 || parsed > 0xffffffffL) throw SecurityAgreementException("invalid Security-Server SPI")
        return parsed
    }

    private fun decimalPort(value: String): Int {
        val parsed = value.toIntOrNull() ?: throw SecurityAgreementException("invalid Security-Server port")
        if (parsed < 1 || parsed > 65535) throw SecurityAgreementException("invalid Security-Server port")
        return parsed
    }

    private fun preference(value: String): Int {
        if (value.isEmpty()) return 0
        val dot = value.indexOf('.')
        val whole = if (dot >= 0) value.substring(0, dot) else value
        if (whole != "0" && whole != "1") throw SecurityAgreementException("invalid Security-Server preference")
        if (dot < 0) return if (whole == "1") 1000 else 0
        var fraction = value.substring(dot + 1)
        if (fraction.length > 3) throw SecurityAgreementException("invalid Security-Server preference")
        while (fraction.length < 3) fraction += "0"
        val numeric = fraction.toIntOrNull() ?: throw SecurityAgreementException("invalid Security-Server preference")
        if (whole == "1" && numeric != 0) throw SecurityAgreementException("invalid Security-Server preference")
        return if (whole == "1") 1000 else numeric
    }

    /** Expands CK/IK into the ESP keys for the negotiated algorithms (TS 33.203 Annex I). */
    fun expandKeys(ck: ByteArray, ik: ByteArray, encryption: String, integrity: String): Pair<ByteArray, ByteArray> {
        if (ck.size != 16 || ik.size != 16) throw SecurityAgreementException("AKA did not return 16-byte CK and IK")
        val encryptionKey = when (encryption.trim().lowercase()) {
            "null" -> ByteArray(0)
            "aes-cbc", "" -> ck.copyOf()
            "des-ede3-cbc" -> {
                val key = ByteArray(24)
                System.arraycopy(ck, 0, key, 0, 16)
                System.arraycopy(ck, 0, key, 16, 8)
                for (index in key.indices) key[index] = oddParity(key[index])
                key
            }
            else -> throw SecurityAgreementException("unsupported ipsec-3gpp encryption algorithm")
        }
        val integrityKey = when (integrity.trim().lowercase()) {
            "hmac-md5-96" -> ik.copyOf()
            "hmac-sha-1-96", "" -> ByteArray(20).also { System.arraycopy(ik, 0, it, 0, 16) }
            else -> throw SecurityAgreementException("unsupported ipsec-3gpp integrity algorithm")
        }
        return encryptionKey to integrityKey
    }

    private fun oddParity(value: Byte): Byte {
        var v = value.toInt() and 0xfe
        var ones = 0
        var bits = v
        while (bits != 0) {
            ones += bits and 1
            bits = bits ushr 1
        }
        if (ones % 2 == 0) v = v or 1
        return v.toByte()
    }
}
