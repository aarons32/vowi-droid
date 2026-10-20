package com.vowi.vowidroid.core.ike

import com.vowi.vowidroid.core.util.RandomSource
import com.vowi.vowidroid.core.util.Rng

/**
 * One IKE SA's keys and message-ID bookkeeping. `originalInitiator` is false
 * after the ePDG rekeys the IKE SA, because the rekeying side becomes the
 * new SA's initiator (RFC 7296 2.18); that flips the header flags and which
 * half of the keys each side uses.
 */
class IkeSa(
    val initiatorSpi: ByteArray,
    val responderSpi: ByteArray,
    val suite: IkeSuite,
    val keys: IkeKeys,
    val originalInitiator: Boolean,
    val fragmentation: Boolean,
) {
    /** Our next request's message ID. */
    var nextRequestId = 0L

    /** The message ID we expect on the ePDG's next request. */
    var nextPeerRequestId = 0L

    /** The last response we sent, resent when the ePDG retransmits its request. */
    var lastResponse: List<ByteArray>? = null
    var lastResponseId = -1L

    private val sendEncryptionKey get() = if (originalInitiator) keys.skei else keys.sker
    private val sendIntegrityKey get() = if (originalInitiator) keys.skai else keys.skar
    private val receiveEncryptionKey get() = if (originalInitiator) keys.sker else keys.skei
    private val receiveIntegrityKey get() = if (originalInitiator) keys.skar else keys.skai

    fun header(exchange: Int, messageId: Long, response: Boolean): IkeHeader {
        var flags = if (originalInitiator) Ike.FLAG_INITIATOR else 0
        if (response) flags = flags or Ike.FLAG_RESPONSE
        return IkeHeader(initiatorSpi = initiatorSpi, responderSpi = responderSpi, exchange = exchange, flags = flags, messageId = messageId)
    }

    fun encrypt(header: IkeHeader, payloads: List<Payload>, random: RandomSource = Rng::bytes): List<ByteArray> =
        if (fragmentation) {
            IkeCrypto.encryptPayloadsFragmented(header, payloads, suite, sendEncryptionKey, sendIntegrityKey, Ike.DEFAULT_FRAGMENT_SIZE, random)
        } else {
            listOf(IkeCrypto.encryptPayloads(header, payloads, suite, sendEncryptionKey, sendIntegrityKey, random))
        }

    fun decrypt(packets: List<ByteArray>): Pair<IkeHeader, List<Payload>> =
        IkeCrypto.decryptAny(packets, suite, receiveEncryptionKey, receiveIntegrityKey)

    /** True when a header belongs to this SA and was sent by the ePDG. */
    fun owns(header: IkeHeader): Boolean =
        header.initiatorSpi.contentEquals(initiatorSpi) && header.responderSpi.contentEquals(responderSpi) &&
            header.fromInitiator != originalInitiator

    fun isResponseTo(header: IkeHeader, exchange: Int, messageId: Long): Boolean =
        owns(header) && header.isResponse && header.exchange == exchange && header.messageId == messageId
}
