package com.vowi.vowidroid.core.ike

import com.vowi.vowidroid.core.carrier.CarrierProfiles
import com.vowi.vowidroid.core.util.CoreLog
import com.google.gson.JsonParser
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

/**
 * Resolves an ePDG hostname. Lookups go through the Wi-Fi network's own
 * resolver, because the carrier's DNS answer depends on where the query comes
 * from. When the hostname does not resolve there, a DNS-over-HTTPS query with
 * the carrier's EDNS client subnet is tried.
 *
 * Every address is returned, not just the first: load-balanced ePDGs publish
 * several and some of them do not answer IKE_AUTH.
 */
class EpdgResolver(
    /** Resolves a name on the chosen network; `InetAddress.getAllByName` by default. */
    private val lookup: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
    private val httpsQuery: (String, String) -> List<InetAddress> = ::resolveWithClientSubnet,
) {
    fun resolve(host: String): List<InetAddress> {
        val normalized = host.trim().trimEnd('.').lowercase()
        require(normalized.isNotEmpty()) { "ePDG hostname is empty" }
        val candidates = ArrayList<String>()
        candidates.add(normalized)
        alternate3gppHostname(normalized)?.let { if (it != normalized) candidates.add(it) }

        var systemError: Exception? = null
        for (candidate in candidates) {
            try {
                val addresses = usable(lookup(candidate).toList())
                if (addresses.isNotEmpty()) return addresses
            } catch (error: Exception) {
                systemError = error
            }
        }
        val subnet = CarrierProfiles.epdgDnsClientSubnet(normalized)
        var fallbackError: Exception? = null
        for (candidate in candidates) {
            try {
                val addresses = usable(httpsQuery(candidate, subnet))
                if (addresses.isNotEmpty()) {
                    CoreLog.i(TAG, "resolved $candidate through DNS-over-HTTPS (${addresses.size} addresses)")
                    return addresses
                }
            } catch (error: Exception) {
                fallbackError = error
            }
        }
        throw IOException(
            "could not resolve the ePDG $normalized: " +
                "network DNS ${systemError?.message ?: "returned no usable address"}, " +
                "DNS-over-HTTPS ${fallbackError?.message ?: "returned no address"}",
        )
    }

    private fun usable(addresses: List<InetAddress>): List<InetAddress> {
        val result = ArrayList<InetAddress>()
        for (address in addresses) {
            if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress) continue
            if (result.none { it.address.contentEquals(address.address) }) result.add(address)
        }
        // IPv4 first: the ePDG's IPv6 SWu endpoint is often unreachable from Wi-Fi.
        return result.sortedBy { if (it is Inet4Address) 0 else 1 }
    }

    companion object {
        private const val TAG = "IKE"
        private const val DNS_OVER_HTTPS = "https://dns.google/resolve"

        /**
         * 3GPP allows both the two and three digit MNC spellings of an ePDG
         * name; carriers publish only one of them.
         */
        fun alternate3gppHostname(host: String): String? {
            val prefix = "epdg.epc.mnc"
            if (!host.startsWith(prefix)) return null
            val rest = host.substring(prefix.length)
            val dot = rest.indexOf('.')
            if (dot <= 0) return null
            val mnc = rest.substring(0, dot)
            val suffix = rest.substring(dot)
            return when {
                mnc.length == 3 && mnc.startsWith("0") -> prefix + mnc.substring(1) + suffix
                mnc.length == 2 -> prefix + "0" + mnc + suffix
                else -> null
            }
        }

        /** A DNS-over-HTTPS A/AAAA query, optionally from the carrier's own subnet. */
        fun resolveWithClientSubnet(host: String, subnet: String): List<InetAddress> {
            val query = StringBuilder(DNS_OVER_HTTPS)
            query.append("?name=").append(URLEncoder.encode(host, "UTF-8")).append("&type=A")
            if (subnet.isNotBlank()) query.append("&edns_client_subnet=").append(URLEncoder.encode(subnet.trim(), "UTF-8"))
            val connection = URL(query.toString()).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/dns-json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            try {
                if (connection.responseCode != 200) throw IOException("DNS-over-HTTPS returned HTTP ${connection.responseCode}")
                val document = InputStreamReader(connection.inputStream, Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
                val status = document.get("Status")?.asInt ?: -1
                if (status != 0) throw IOException("DNS-over-HTTPS returned DNS status $status")
                val result = ArrayList<InetAddress>()
                document.getAsJsonArray("Answer")?.forEach { element ->
                    val answer = element.asJsonObject
                    val type = answer.get("type")?.asInt ?: 0
                    if (type != 1 && type != 28) return@forEach
                    val data = answer.get("data")?.asString?.trim()?.trimEnd('.') ?: return@forEach
                    runCatching { InetAddress.getByName(data) }.getOrNull()?.let { address ->
                        if (result.none { it.address.contentEquals(address.address) }) result.add(address)
                    }
                }
                if (result.isEmpty()) throw IOException("DNS-over-HTTPS response contained no ePDG address")
                return result
            } finally {
                connection.disconnect()
            }
        }
    }
}
