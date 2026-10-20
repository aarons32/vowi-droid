package com.vowi.vowidroid.core.ike

import com.vowi.vowidroid.core.util.RandomSource
import com.vowi.vowidroid.core.util.Rng
import com.vowi.vowidroid.core.util.concat
import com.vowi.vowidroid.core.util.constantTimeEquals
import com.vowi.vowidroid.core.util.hexToBytes
import com.vowi.vowidroid.core.util.putU16
import com.vowi.vowidroid.core.util.u16
import com.vowi.vowidroid.core.util.u8
import java.math.BigInteger
import java.net.InetAddress
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class IkeKeys(
    val skd: ByteArray,
    val skai: ByteArray,
    val skar: ByteArray,
    val skei: ByteArray,
    val sker: ByteArray,
    val skpi: ByteArray,
    val skpr: ByteArray,
)

/** Algorithm properties of a negotiated IKE suite, from [IkeAlgorithms]. */
val IkeSuite.prfAlgorithm: String get() = IkeAlgorithms.prf(prfId).jceName

val IkeSuite.prfLength: Int get() = IkeAlgorithms.prf(prfId).outputLength

val IkeSuite.encryptionKeyLength: Int
    get() {
        if (encryptionBits !in IkeAlgorithms.encryptionBits) throw IkeUnsupportedSuiteException("AES key length $encryptionBits")
        return encryptionBits / 8
    }

val IkeSuite.integrityAlgorithm: String get() = IkeAlgorithms.integrity(integrityId).jceName

val IkeSuite.integrityKeyLength: Int get() = IkeAlgorithms.integrity(integrityId).keyLength

val IkeSuite.checksumLength: Int get() = IkeAlgorithms.integrity(integrityId).checksumLength

val IkeSuite.encryptionName: String get() = IkeAlgorithms.encryptionName(encryptionBits)

val IkeSuite.prfName: String get() = IkeAlgorithms.prfName(prfId)

val IkeSuite.integrityName: String get() = IkeAlgorithms.integrityName(integrityId)

val IkeSuite.dhName: String get() = IkeAlgorithms.dhName(dhId)

object IkeCrypto {
    private const val AES_BLOCK = 16

    fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(data)
    }

    fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun aesCbc(encrypt: Boolean, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    fun prf(suite: IkeSuite, key: ByteArray, data: ByteArray): ByteArray = hmac(suite.prfAlgorithm, key, data)

    fun prfPlus(suite: IkeSuite, key: ByteArray, seed: ByteArray, length: Int): ByteArray {
        require(length >= 0) { "negative key stream length" }
        val result = ByteArray(length)
        var produced = 0
        var previous = ByteArray(0)
        var counter = 1
        while (produced < length) {
            if (counter > 255) throw IkeException("ike: PRF+ output is too long")
            val block = prf(suite, key, concat(previous, seed, byteArrayOf(counter.toByte())))
            val take = minOf(block.size, length - produced)
            System.arraycopy(block, 0, result, produced, take)
            produced += take
            previous = block
            counter++
        }
        return result
    }

    fun deriveIkeKeys(
        suite: IkeSuite,
        sharedSecret: ByteArray,
        initiatorNonce: ByteArray,
        responderNonce: ByteArray,
        initiatorSpi: ByteArray,
        responderSpi: ByteArray,
    ): IkeKeys {
        val skeyseed = prf(suite, concat(initiatorNonce, responderNonce), sharedSecret)
        return deriveIkeKeysFromSkeyseed(suite, skeyseed, initiatorNonce, responderNonce, initiatorSpi, responderSpi)
    }

    fun deriveIkeKeysFromSkeyseed(
        suite: IkeSuite,
        skeyseed: ByteArray,
        initiatorNonce: ByteArray,
        responderNonce: ByteArray,
        initiatorSpi: ByteArray,
        responderSpi: ByteArray,
    ): IkeKeys {
        val prfLength = suite.prfLength
        val encryptionLength = suite.encryptionKeyLength
        val integrityLength = suite.integrityKeyLength
        val seed = concat(initiatorNonce, responderNonce, initiatorSpi, responderSpi)
        val total = prfLength + integrityLength * 2 + encryptionLength * 2 + prfLength * 2
        val stream = prfPlus(suite, skeyseed, seed, total)
        var offset = 0
        fun take(length: Int): ByteArray {
            val value = stream.copyOfRange(offset, offset + length)
            offset += length
            return value
        }
        return IkeKeys(
            skd = take(prfLength),
            skai = take(integrityLength),
            skar = take(integrityLength),
            skei = take(encryptionLength),
            sker = take(encryptionLength),
            skpi = take(prfLength),
            skpr = take(prfLength),
        )
    }

    fun integrityMac(suite: IkeSuite, key: ByteArray, packetWithoutChecksum: ByteArray): ByteArray =
        hmac(suite.integrityAlgorithm, key, packetWithoutChecksum).copyOf(suite.checksumLength)

    /** Builds one SK{...} packet. */
    fun encryptPayloads(
        header: IkeHeader,
        inner: List<Payload>,
        suite: IkeSuite,
        encryptionKey: ByteArray,
        integrityKey: ByteArray,
        random: RandomSource = Rng::bytes,
    ): ByteArray {
        val (first, plaintext) = IkeWire.marshalPayloadChain(inner)
        val padded = padPlaintext(plaintext, random)
        val iv = random(AES_BLOCK)
        val ciphertext = aesCbc(true, encryptionKey, iv, padded)
        val checksumLength = suite.checksumLength
        val skLength = 4 + iv.size + ciphertext.size + checksumLength
        if (skLength > 65535) throw IkeException("ike: encrypted payload exceeds 65535 bytes")
        val body = ByteArray(skLength)
        body[0] = first.toByte()
        body[1] = 0
        body.putU16(2, skLength)
        System.arraycopy(iv, 0, body, 4, iv.size)
        System.arraycopy(ciphertext, 0, body, 4 + iv.size, ciphertext.size)
        val packet = header.copy(nextPayload = Ike.PAYLOAD_ENCRYPTED).marshal(body)
        val checksum = integrityMac(suite, integrityKey, packet.copyOf(packet.size - checksumLength))
        System.arraycopy(checksum, 0, packet, packet.size - checksumLength, checksumLength)
        return packet
    }

    private fun padPlaintext(plaintext: ByteArray, random: RandomSource): ByteArray {
        var paddingLength = AES_BLOCK - (plaintext.size + 1) % AES_BLOCK
        if (paddingLength == AES_BLOCK) paddingLength = 0
        return concat(plaintext, random(paddingLength), byteArrayOf(paddingLength.toByte()))
    }

    /** Verifies and decrypts one SK{...} packet. */
    fun decryptPayloads(packet: ByteArray, suite: IkeSuite, encryptionKey: ByteArray, integrityKey: ByteArray): Pair<IkeHeader, List<Payload>> {
        val (header, body) = IkeHeader.parse(packet)
        if (header.nextPayload != Ike.PAYLOAD_ENCRYPTED || body.size < 4) {
            throw IkeUnexpectedPacketException("message is not an encrypted IKE payload")
        }
        val skLength = body.u16(2)
        if (skLength != body.size) throw IkeMalformedException("encrypted payload length mismatch")
        val checksumLength = suite.checksumLength
        if (body.size < 4 + AES_BLOCK + AES_BLOCK + checksumLength) throw IkeMalformedException("encrypted payload is too short")
        verifyChecksum(packet, suite, integrityKey, checksumLength)
        val iv = body.copyOfRange(4, 4 + AES_BLOCK)
        val ciphertext = body.copyOfRange(4 + AES_BLOCK, body.size - checksumLength)
        val plaintext = unpad(aesCbc(false, encryptionKey, iv, ciphertext))
        return header to IkeWire.parsePayloadChain(body.u8(0), plaintext)
    }

    private fun verifyChecksum(packet: ByteArray, suite: IkeSuite, integrityKey: ByteArray, checksumLength: Int) {
        val expected = integrityMac(suite, integrityKey, packet.copyOf(packet.size - checksumLength))
        val actual = packet.copyOfRange(packet.size - checksumLength, packet.size)
        if (!constantTimeEquals(actual, expected)) throw IkeIntegrityException()
    }

    private fun unpad(plaintext: ByteArray): ByteArray {
        if (plaintext.isEmpty() || plaintext.size % AES_BLOCK != 0) throw IkeMalformedException("ciphertext is not block aligned")
        val paddingLength = plaintext.u8(plaintext.size - 1)
        if (paddingLength + 1 > plaintext.size) throw IkeMalformedException("invalid encrypted payload padding")
        return plaintext.copyOf(plaintext.size - paddingLength - 1)
    }

    /** RFC 7383: splits the plaintext across SKF payloads when it exceeds the fragment size. */
    fun encryptPayloadsFragmented(
        header: IkeHeader,
        inner: List<Payload>,
        suite: IkeSuite,
        encryptionKey: ByteArray,
        integrityKey: ByteArray,
        maxFragmentSize: Int = Ike.DEFAULT_FRAGMENT_SIZE,
        random: RandomSource = Rng::bytes,
    ): List<ByteArray> {
        val fragmentSize = if (maxFragmentSize <= 0) Ike.DEFAULT_FRAGMENT_SIZE else maxFragmentSize
        val (first, plaintext) = IkeWire.marshalPayloadChain(inner)
        val checksumLength = suite.checksumLength
        val maxChunk = maxOf(64, fragmentSize - Ike.HEADER_LENGTH - 8 - AES_BLOCK - AES_BLOCK - checksumLength)
        val chunks = ArrayList<ByteArray>()
        var offset = 0
        while (offset < plaintext.size) {
            val take = minOf(maxChunk, plaintext.size - offset)
            chunks.add(plaintext.copyOfRange(offset, offset + take))
            offset += take
        }
        if (chunks.size <= 1) return listOf(encryptPayloads(header, inner, suite, encryptionKey, integrityKey, random))
        val total = chunks.size
        return chunks.mapIndexed { index, chunk ->
            val fragmentNumber = index + 1
            val fragmentNext = if (fragmentNumber == 1) first else Ike.PAYLOAD_NONE
            val padded = padPlaintext(chunk, random)
            val iv = random(AES_BLOCK)
            val ciphertext = aesCbc(true, encryptionKey, iv, padded)
            val skfLength = 4 + 4 + iv.size + ciphertext.size + checksumLength
            if (skfLength > 65535) throw IkeException("ike: encrypted fragment exceeds 65535 bytes")
            val body = ByteArray(skfLength)
            body[0] = fragmentNext.toByte()
            body.putU16(2, skfLength)
            body.putU16(4, fragmentNumber)
            body.putU16(6, total)
            System.arraycopy(iv, 0, body, 8, iv.size)
            System.arraycopy(ciphertext, 0, body, 8 + iv.size, ciphertext.size)
            val packet = header.copy(nextPayload = Ike.PAYLOAD_ENCRYPTED_FRAGMENT).marshal(body)
            val checksum = integrityMac(suite, integrityKey, packet.copyOf(packet.size - checksumLength))
            System.arraycopy(checksum, 0, packet, packet.size - checksumLength, checksumLength)
            packet
        }
    }

    class Fragment(val header: IkeHeader, val next: Int, val number: Int, val total: Int, val plaintext: ByteArray)

    fun decryptFragment(packet: ByteArray, suite: IkeSuite, encryptionKey: ByteArray, integrityKey: ByteArray): Fragment {
        val (header, body) = IkeHeader.parse(packet)
        if (header.nextPayload != Ike.PAYLOAD_ENCRYPTED_FRAGMENT || body.size < 8) {
            throw IkeUnexpectedPacketException("message is not an encrypted IKE fragment")
        }
        if (body.u16(2) != body.size) throw IkeMalformedException("encrypted fragment length mismatch")
        val checksumLength = suite.checksumLength
        if (body.size < 8 + AES_BLOCK + AES_BLOCK + checksumLength) throw IkeMalformedException("encrypted fragment is too short")
        verifyChecksum(packet, suite, integrityKey, checksumLength)
        val number = body.u16(4)
        val total = body.u16(6)
        if (number == 0 || total == 0 || number > total) throw IkeMalformedException("invalid fragment numbers $number/$total")
        val iv = body.copyOfRange(8, 8 + AES_BLOCK)
        val ciphertext = body.copyOfRange(8 + AES_BLOCK, body.size - checksumLength)
        val plaintext = unpad(aesCbc(false, encryptionKey, iv, ciphertext))
        return Fragment(header, body.u8(0), number, total, plaintext)
    }

    /** Decrypts a complete message that arrived as one packet or as a set of SKF fragments. */
    fun decryptAny(packets: List<ByteArray>, suite: IkeSuite, encryptionKey: ByteArray, integrityKey: ByteArray): Pair<IkeHeader, List<Payload>> {
        if (packets.isEmpty()) throw IkeException("ike: empty exchange response")
        if (packets.size == 1) {
            val (header, _) = IkeHeader.parse(packets[0])
            if (header.nextPayload != Ike.PAYLOAD_ENCRYPTED_FRAGMENT) {
                return decryptPayloads(packets[0], suite, encryptionKey, integrityKey)
            }
            val fragment = decryptFragment(packets[0], suite, encryptionKey, integrityKey)
            if (fragment.number != 1 || fragment.total != 1) {
                throw IkeMalformedException("standalone fragment with total=${fragment.total}")
            }
            return fragment.header to IkeWire.parsePayloadChain(fragment.next, fragment.plaintext)
        }
        var firstHeader: IkeHeader? = null
        var firstNext = Ike.PAYLOAD_NONE
        var totalExpected = 0
        val plaintexts = HashMap<Int, ByteArray>()
        for (packet in packets) {
            val fragment = decryptFragment(packet, suite, encryptionKey, integrityKey)
            if (firstHeader == null) {
                firstHeader = fragment.header
                totalExpected = fragment.total
            } else if (fragment.total != totalExpected || fragment.header.messageId != firstHeader.messageId ||
                fragment.header.exchange != firstHeader.exchange
            ) {
                throw IkeMalformedException("inconsistent fragment headers")
            }
            if (fragment.number == 1) firstNext = fragment.next
            plaintexts[fragment.number] = fragment.plaintext
        }
        if (plaintexts.size != totalExpected) {
            throw IkeMalformedException("missing fragments: received ${plaintexts.size} of $totalExpected")
        }
        val full = concat(*(1..totalExpected).map { plaintexts[it] ?: throw IkeMalformedException("missing fragment $it") }.toTypedArray())
        return firstHeader!! to IkeWire.parsePayloadChain(firstNext, full)
    }

    fun natDetectionHash(initiatorSpi: ByteArray, responderSpi: ByteArray, address: InetAddress, port: Int): ByteArray =
        sha1(concat(initiatorSpi, responderSpi, address.address, byteArrayOf((port ushr 8).toByte(), port.toByte())))
}

/** A MODP Diffie-Hellman exchange over the RFC 2409 and RFC 3526 groups (2, 5, 14, 15, 16 and 18). */
class DhExchange(val group: Int, random: RandomSource = Rng::bytes) {
    private val prime: BigInteger
    private val privateValue: BigInteger
    val public: ByteArray

    init {
        val primeHex = MODP_PRIMES[group] ?: throw IkeUnsupportedSuiteException("DH group $group")
        val primeBytes = primeHex.hexToBytes()
        prime = BigInteger(1, primeBytes)
        val sample = random(primeBytes.size)
        privateValue = BigInteger(1, sample).mod(prime.subtract(BigInteger.valueOf(3))).add(BigInteger.valueOf(2))
        public = BigInteger.valueOf(2).modPow(privateValue, prime).toFixedLength(primeBytes.size)
    }

    val primeBitLength: Int get() = prime.bitLength()

    fun shared(peerPublic: ByteArray): ByteArray {
        if (peerPublic.size != public.size) {
            throw IkeException("ike: peer DH value length ${peerPublic.size} does not match group length ${public.size}")
        }
        val peer = BigInteger(1, peerPublic)
        val upper = prime.subtract(BigInteger.valueOf(2))
        if (peer < BigInteger.valueOf(2) || peer > upper) throw IkeException("ike: peer DH public value is outside the safe range")
        val shared = peer.modPow(privateValue, prime)
        if (shared.signum() == 0 || shared == BigInteger.ONE) throw IkeException("ike: invalid trivial DH shared secret")
        return shared.toFixedLength(public.size)
    }

    companion object {
        val MODP_PRIMES: Map<Int, String> = mapOf(
            Ike.DH_MODP1024 to "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
                "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
                "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE65381" +
                "FFFFFFFFFFFFFFFF",
            Ike.DH_MODP2048 to "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
                "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
                "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
                "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
                "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
                "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                "15728E5A8AACAA68FFFFFFFFFFFFFFFF",
            Ike.DH_MODP1536 to "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
                "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
                "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
                "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
                "670C354E4ABC9804F1746C08CA237327FFFFFFFFFFFFFFFF",
            Ike.DH_MODP3072 to "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
                "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
                "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
                "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
                "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
                "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64" +
                "ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6B" +
                "F12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB31" +
                "43DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
            Ike.DH_MODP4096 to "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
                "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
                "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
                "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
                "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
                "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64" +
                "ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6B" +
                "F12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB31" +
                "43DB5BFCE0FD108E4B82D120A92108011A723C12A787E6D7" +
                "88719A10BDBA5B2699C327186AF4E23C1A946834B6150BDA" +
                "2583E9CA2AD44CE8DBBBC2DB04DE8EF92E8EFC141FBECAA6" +
                "287C59474E6BC05D99B2964FA090C3A2233BA186515BE7ED" +
                "1F612970CEE2D7AFB81BDD762170481CD0069127D5B05AA9" +
                "93B4EA988D8FDDC186FFB7DC90A6C08F4DF435C934063199" +
                "FFFFFFFFFFFFFFFF",
            Ike.DH_MODP8192 to "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
                "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
                "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
                "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
                "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
                "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64" +
                "ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6B" +
                "F12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB31" +
                "43DB5BFCE0FD108E4B82D120A92108011A723C12A787E6D7" +
                "88719A10BDBA5B2699C327186AF4E23C1A946834B6150BDA" +
                "2583E9CA2AD44CE8DBBBC2DB04DE8EF92E8EFC141FBECAA6" +
                "287C59474E6BC05D99B2964FA090C3A2233BA186515BE7ED" +
                "1F612970CEE2D7AFB81BDD762170481CD0069127D5B05AA9" +
                "93B4EA988D8FDDC186FFB7DC90A6C08F4DF435C934028492" +
                "36C3FAB4D27C7026C1D4DCB2602646DEC9751E763DBA37BD" +
                "F8FF9406AD9E530EE5DB382F413001AEB06A53ED9027D831" +
                "179727B0865A8918DA3EDBEBCF9B14ED44CE6CBACED4BB1B" +
                "DB7F1447E6CC254B332051512BD7AF426FB8F401378CD2BF" +
                "5983CA01C64B92ECF032EA15D1721D03F482D7CE6E74FEF6" +
                "D55E702F46980C82B5A84031900B1C9E59E7C97FBEC7E8F3" +
                "23A97A7E36CC88BE0F1D45B7FF585AC54BD407B22B4154AA" +
                "CC8F6D7EBF48E1D814CC5ED20F8037E0A79715EEF29BE328" +
                "06A1D58BB7C5DA76F550AA3D8A1FBFF0EB19CCB1A313D55C" +
                "DA56C9EC2EF29632387FE8D76E3C0468043E8F663F4860EE" +
                "12BF2D5B0B7474D6E694F91E6DBE115974A3926F12FEE5E4" +
                "38777CB6A932DF8CD8BEC4D073B931BA3BC832B68D9DD300" +
                "741FA7BF8AFC47ED2576F6936BA424663AAB639C5AE4F568" +
                "3423B4742BF1C978238F16CBE39D652DE3FDB8BEFC848AD9" +
                "22222E04A4037C0713EB57A81A23F0C73473FC646CEA306B" +
                "4BCBC8862F8385DDFA9D4B7FA2C087E879683303ED5BDD3A" +
                "062B3CF5B3A278A66D2A13F83F44F82DDF310EE074AB6A36" +
                "4597E899A0255DC164F31CC50846851DF9AB48195DED7EA1" +
                "B1D510BD7EE74D73FAF36BC31ECFA268359046F4EB879F92" +
                "4009438B481C6CD7889A002ED5EE382BC9190DA6FC026E47" +
                "9558E4475677E9AA9E3050E2765694DFC81F56E880B96E71" +
                "60C980DD98EDD3DFFFFFFFFFFFFFFFFF",
        )
    }
}

/** Big-endian magnitude of a non-negative integer, left padded to `length` bytes. */
fun BigInteger.toFixedLength(length: Int): ByteArray {
    val raw = toByteArray()
    val out = ByteArray(length)
    val start = if (raw.size > length) raw.size - length else 0
    val copyLength = raw.size - start
    System.arraycopy(raw, start, out, length - copyLength, copyLength)
    return out
}
