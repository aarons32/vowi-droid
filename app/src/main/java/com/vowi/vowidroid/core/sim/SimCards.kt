package com.vowi.vowidroid.core.sim

import com.vowi.vowidroid.core.util.toHex
import java.security.MessageDigest

/**
 * A line is an account: the subscriber the IMSI names. The ICCID names the
 * card that subscriber is on right now. The line follows its IMSI from one
 * SIM slot to another or into a USB reader, and on to a replacement card
 * when the carrier keeps the IMSI.
 */
object SimCards {
    /** Why a card that is in cannot be read right now. */
    const val ISSUE_AIRPLANE = "airplane"
    const val ISSUE_ACCESS = "access"
    const val ISSUE_PIN = "pin"
    const val ISSUE_OFF = "off"

    /**
     * The line ID for the subscriber [imsi]: the same IMSI always gets the
     * same ID, so re-adding it finds its history. The IMSI itself stays out
     * of IDs, which reach Telecom and the logs.
     */
    fun lineId(imsi: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("vowi-line:${imsi.trim()}".toByteArray(Charsets.US_ASCII))
        return "sim-" + digest.toHex().take(12)
    }
}

/** Where a card is right now. */
sealed interface SimLocation {
    /** A short description to remember where a card was last seen, e.g. "slot:2". */
    val tag: String

    /** One of the phone's own SIM slots, zero-based, numbered as the phone's own Settings number them. */
    data class PhoneSlot(val slot: Int) : SimLocation {
        override val tag: String get() = "slot:${slot + 1}"
    }

    /** A USB SIM reader; [device] is Android's USB device name, which changes when it is plugged in again. */
    data class UsbReader(val device: String, val name: String) : SimLocation {
        override val tag: String get() = "usb:$name"
    }
}

/**
 * A card found in a slot or reader. [imsi] is null when the card could not
 * be read, e.g. a PIN-locked card in a reader (which still gives its ICCID)
 * or a card the phone switched off (which gives nothing).
 */
data class FoundCard(
    val location: SimLocation,
    val imsi: String?,
    val iccid: String,
    /** A label such as the carrier's name. */
    val label: String,
    /** Why the card could not be read: one of SimCards.ISSUE_*, or empty. */
    val issue: String = "",
    /** Android's subscription ID for a card in a phone slot, or -1. */
    val subscriptionId: Int = -1,
    /** Read over the card's own OMAPI channel rather than through Android telephony; see OmapiSims. */
    val direct: Boolean = false,
) {
    /** VowiDroid read the card and can use it. */
    val readable: Boolean get() = imsi != null
}

/** A saved line as the matcher sees it. A line saved before cards were tracked has no IMSI; its old slot stands in once. */
data class SavedLine(val id: String, val imsi: String?, val iccid: String?, val legacySlot: Int?, val lastSeen: String?)

/** The subscriber and card a line is on. */
data class CardIdentity(val imsi: String, val iccid: String)

/** Which line's card is where. */
data class CardMatch(
    /** Where each line's card is now, with what could be read of it. A line whose card was not found has no entry. */
    val placed: Map<String, FoundCard>,
    /** Lines whose record changes: a legacy line now tied to its subscriber, or a replacement card with the same IMSI. */
    val bindings: Map<String, CardIdentity>,
    /** Cards no line belongs to: new SIMs the user may add, locked cards included so the reason can be shown. */
    val unknown: List<FoundCard>,
)

object CardMatcher {
    fun match(lines: List<SavedLine>, cards: List<FoundCard>): CardMatch {
        val placed = HashMap<String, FoundCard>()
        val bindings = HashMap<String, CardIdentity>()
        val unclaimed = cards.toMutableList()

        fun claim(line: SavedLine, card: FoundCard) {
            placed[line.id] = card
            unclaimed.remove(card)
        }

        // The subscriber: a line follows its IMSI anywhere, also onto a replacement card.
        for (line in lines) {
            val imsi = line.imsi ?: continue
            val card = unclaimed.firstOrNull { it.imsi == imsi } ?: continue
            claim(line, card)
            if (card.iccid.isNotEmpty() && !card.iccid.equals(line.iccid, ignoreCase = true)) bindings[line.id] = CardIdentity(imsi, card.iccid)
        }
        // The card: a locked card in a reader gives only its ICCID, which still says whose it is.
        for (line in lines) {
            if (line.id in placed) continue
            val iccid = line.iccid?.takeIf { it.isNotEmpty() } ?: continue
            val card = unclaimed.firstOrNull { !it.readable && it.iccid.equals(iccid, ignoreCase = true) } ?: continue
            claim(line, card)
        }
        // The place: a card that gives nothing, e.g. switched off, is the one last seen there.
        for (line in lines) {
            if (line.id in placed) continue
            val tag = line.lastSeen ?: continue
            val card = unclaimed.firstOrNull { !it.readable && it.iccid.isEmpty() && it.location.tag == tag } ?: continue
            claim(line, card)
        }
        // Lines saved before cards were tracked: the card in their old slot, or, for a lone line, the lone card. The
        // phone may number its slots differently from one day to the next, so the slot alone is not trusted for long.
        val legacy = lines.filter { it.imsi == null && it.iccid.isNullOrEmpty() && it.id !in placed }
        for (line in legacy) {
            val slot = line.legacySlot ?: continue
            val card = unclaimed.firstOrNull { it.readable && it.location == SimLocation.PhoneSlot(slot) } ?: continue
            claim(line, card)
            bindings[line.id] = CardIdentity(card.imsi!!, card.iccid)
        }
        val lone = legacy.filter { it.id !in placed }.singleOrNull()
        val loneCard = unclaimed.filter { it.readable }.singleOrNull()
        if (lone != null && loneCard != null) {
            claim(lone, loneCard)
            bindings[lone.id] = CardIdentity(loneCard.imsi!!, loneCard.iccid)
        }
        return CardMatch(placed, bindings, unclaimed.filter { it.readable || it.location is SimLocation.UsbReader })
    }

    /**
     * An ejected card stays ejected while it is still in. Taking it out ends
     * that, so putting it back in, anywhere, brings its line back.
     */
    fun stillEjected(ejected: Boolean, present: Boolean): Boolean = ejected && present
}
