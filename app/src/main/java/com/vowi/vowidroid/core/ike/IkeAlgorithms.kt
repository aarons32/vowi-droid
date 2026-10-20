package com.vowi.vowidroid.core.ike

/**
 * The IKEv2 transform IDs this stack implements (IANA "IKEv2 Transform Type
 * Values"), with the names the carrier profiles and the tunnel evidence use
 * for them. Every suite check reads these tables, so an algorithm is added
 * here once, next to its primitive.
 */
object IkeAlgorithms {
    class Prf(val id: Int, val name: String, val jceName: String, val outputLength: Int)
    class Integrity(val id: Int, val name: String, val jceName: String, val keyLength: Int, val checksumLength: Int)
    class DhGroup(val id: Int, val name: String, val bits: Int)

    /** The only cipher offered; AES-GCM never appears in the carrier bundles. */
    const val ENCRYPTION_NAME = "aes-cbc"
    val encryptionBits: List<Int> = listOf(128, 256)

    val prfs: List<Prf> = listOf(
        Prf(Ike.PRF_HMAC_SHA1, "hmac-sha1", "HmacSHA1", 20),
        Prf(Ike.PRF_HMAC_SHA256, "hmac-sha2-256", "HmacSHA256", 32),
        Prf(Ike.PRF_HMAC_SHA384, "hmac-sha2-384", "HmacSHA384", 48),
        Prf(Ike.PRF_HMAC_SHA512, "hmac-sha2-512", "HmacSHA512", 64),
    )

    /** RFC 4868 truncations: half the digest for SHA-2, 96 bits for SHA-1. */
    val integrities: List<Integrity> = listOf(
        Integrity(Ike.INTEGRITY_HMAC_SHA1_96, "hmac-sha1-96", "HmacSHA1", 20, 12),
        Integrity(Ike.INTEGRITY_HMAC_SHA256_128, "hmac-sha2-256-128", "HmacSHA256", 32, 16),
        Integrity(Ike.INTEGRITY_HMAC_SHA384_192, "hmac-sha2-384-192", "HmacSHA384", 48, 24),
        Integrity(Ike.INTEGRITY_HMAC_SHA512_256, "hmac-sha2-512-256", "HmacSHA512", 64, 32),
    )

    val dhGroups: List<DhGroup> = listOf(
        DhGroup(Ike.DH_MODP1024, "modp1024", 1024),
        DhGroup(Ike.DH_MODP1536, "modp1536", 1536),
        DhGroup(Ike.DH_MODP2048, "modp2048", 2048),
        DhGroup(Ike.DH_MODP3072, "modp3072", 3072),
        DhGroup(Ike.DH_MODP4096, "modp4096", 4096),
        DhGroup(Ike.DH_MODP8192, "modp8192", 8192),
    )

    fun prf(id: Int): Prf = prfs.firstOrNull { it.id == id } ?: throw IkeUnsupportedSuiteException("PRF id $id")
    fun integrity(id: Int): Integrity = integrities.firstOrNull { it.id == id } ?: throw IkeUnsupportedSuiteException("integrity id $id")
    fun integrityByName(name: String): Integrity? = integrities.firstOrNull { it.name == name.trim().lowercase() }

    fun supportsEncryption(id: Int, bits: Int): Boolean = id == Ike.ENCRYPTION_AES_CBC && bits in encryptionBits
    fun supportsPrf(id: Int): Boolean = prfs.any { it.id == id }
    fun supportsIntegrity(id: Int): Boolean = integrities.any { it.id == id }
    fun supportsDh(id: Int): Boolean = dhGroups.any { it.id == id }

    fun encryptionName(bits: Int): String = "$ENCRYPTION_NAME-$bits"
    fun prfName(id: Int): String = prfs.firstOrNull { it.id == id }?.name.orEmpty()
    fun integrityName(id: Int): String = integrities.firstOrNull { it.id == id }?.name.orEmpty()
    fun dhName(id: Int): String = dhGroups.firstOrNull { it.id == id }?.name.orEmpty()

    /** The transform ID for a profile's algorithm name, or 0 when the name is unknown. */
    fun prfId(name: String): Int = prfs.firstOrNull { it.name == name.trim().lowercase() }?.id ?: 0
    fun integrityId(name: String): Int = integrityByName(name)?.id ?: 0
}
