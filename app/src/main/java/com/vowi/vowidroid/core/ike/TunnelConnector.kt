package com.vowi.vowidroid.core.ike

import com.vowi.vowidroid.core.util.CoreLog
import java.io.IOException
import java.net.InetAddress

/**
 * Brings a tunnel up by trying every ePDG address the name resolves to.
 *
 * For each address it first opens straight onto UDP 4500, so the whole
 * session shares one mapping; if that address never answers, it tries the
 * classic port 500 start once, then moves on. A rejection with a reason
 * (EAP failure, no proposal, authentication failed) stops the search, since
 * another address would say the same. The address that worked is remembered
 * and tried first next time.
 */
class TunnelConnector(
    private val resolver: EpdgResolver = EpdgResolver(),
    /** Persists the last working address per ePDG name. */
    private val memory: AddressMemory = AddressMemory.inProcess(),
) {
    interface AddressMemory {
        fun lastWorking(epdg: String): String?
        fun remember(epdg: String, address: String)

        companion object {
            fun inProcess(): AddressMemory = object : AddressMemory {
                private val map = HashMap<String, String>()
                override fun lastWorking(epdg: String): String? = synchronized(map) { map[epdg] }
                override fun remember(epdg: String, address: String) = synchronized(map) { map[epdg] = address }
            }
        }
    }

    fun connect(params: TunnelParams, cancelled: () -> Boolean = { false }): HandshakeResult {
        val addresses = resolver.resolve(params.epdg).toMutableList()
        memory.lastWorking(params.epdg)?.let { remembered ->
            val index = addresses.indexOfFirst { it.hostAddress == remembered }
            if (index > 0) addresses.add(0, addresses.removeAt(index))
        }
        CoreLog.i(TAG, "ePDG ${params.epdg} has ${addresses.size} address(es); trying ${addresses.first().hostAddress} first")
        var lastError: Exception? = null
        for (address in addresses) {
            if (cancelled()) throw IOException("cancelled")
            for (encapsulated in booleanArrayOf(true, false)) {
                if (cancelled()) throw IOException("cancelled")
                try {
                    val result = attempt(address, encapsulated, params)
                    memory.remember(params.epdg, address.hostAddress ?: "")
                    return result
                } catch (error: Exception) {
                    lastError = error
                    if (!isTimeout(error)) {
                        CoreLog.w(TAG, "ePDG ${address.hostAddress} rejected the tunnel: ${error.message}")
                        throw error
                    }
                    CoreLog.w(TAG, "ePDG ${address.hostAddress} on UDP ${if (encapsulated) 4500 else 500} did not answer: ${error.message}")
                }
            }
        }
        throw IOException("no ePDG address for ${params.epdg} answered", lastError)
    }

    /**
     * One address, one start mode, and the profile's offers in turn: the
     * bundle's exact proposal first, then VowiDroid's own set, and the legacy
     * set for carriers without a profile, each tried only when the ePDG
     * rejected the previous offer outright.
     */
    private fun attempt(address: InetAddress, encapsulated: Boolean, params: TunnelParams): HandshakeResult {
        val transport = IkeTransport.open(address, encapsulated, params.bindSocket)
        try {
            val offers = IkeHandshake.offerChain(params)
            for ((index, offer) in offers.withIndex()) {
                try {
                    return runWithIdentityFallback(transport, params, offer)
                } catch (error: Exception) {
                    if (index + 1 == offers.size || !IkeHandshake.retryableWithAnotherOffer(error)) throw error
                    CoreLog.w(TAG, "the ePDG rejected the ${offer.label} proposal; trying the ${offers[index + 1].label} one: ${error.message}")
                }
            }
            throw IllegalStateException("no IKE offer to send")
        } catch (error: Exception) {
            transport.close()
            throw error
        }
    }

    /** A protected identity the ePDG turns away is tried once more in the clear, so privacy never costs the line. */
    private fun runWithIdentityFallback(transport: IkeTransport, params: TunnelParams, offer: OfferKind): HandshakeResult {
        val protected = !params.plainIdentity && (params.profile.privacy.canEncrypt || params.profile.privacy.anonymousIdi)
        return try {
            IkeHandshake.run(transport, params, offer)
        } catch (error: EapAuthenticationRejectedException) {
            if (!protected) throw error
            CoreLog.w(TAG, "the ePDG refused the protected identity; trying the permanent one in the clear: ${error.message}")
            val result = IkeHandshake.run(transport, params.withPlainIdentity(), offer)
            HandshakeResult(result.sa, result.transport, result.child, result.network, result.evidence.copy(identity = "permanent (the ePDG refused the protected one)"))
        }
    }

    private fun isTimeout(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is java.net.SocketTimeoutException) return true
            if (current.message?.contains("did not answer") == true) return true
            current = current.cause
        }
        return false
    }

    companion object {
        private const val TAG = "IKE"
    }
}
