package com.vowi.vowidroid.data

import java.time.Instant

/** One VowiDroid line: a SIM card, in one of the phone's slots or in a USB SIM reader. */
data class Line(
    val id: String,
    val name: String,
    val kind: Kind,
    val wifiCallingEnabled: Boolean,
    val phase: String,
    val registered: Boolean,
    val smsReady: Boolean,
    val phoneNumber: String,
    val epdg: String,
    val carrierProfile: String,
    val lastError: String,
    val iccid: String,
    /** When the next attempt is due while the line is failed, else 0. */
    val retryAt: Long = 0,
    /** The wait before the next attempt is one the carrier asked for, not VowiDroid's own back-off. */
    val carrierPause: Boolean = false,
    /** The step under way while connecting (sim, carrier, tunnel, ims), the step the last failure hit, and its code. */
    val stage: String = "",
    val failedStage: String = "",
    val errorCode: String = "",
    /** How the subscriber was named to the ePDG. */
    val identity: String = "",
    /** The network the tunnel runs over: "Wi-Fi", "mobile data", "Ethernet" or "VPN". */
    val network: String = "",
    /** The SMS center the SIM or the carrier profile gave, before the user's own entry; and which of them: "sim" or "profile". */
    val smsCenter: String = "",
    val smsCenterFrom: String = "",
    /** The line's card was found in a slot or reader. */
    val present: Boolean,
    /** Where the card is, e.g. "SIM slot 1" or a reader's name; empty when it is not in. */
    val location: String = "",
    /** Where the card was last seen, when it is not in now. */
    val lastSeen: String = "",
    val sim: SimStatus = SimStatus.IN_USE,
    /** Why the card cannot be read when that is known: one of SimCards.ISSUE_*. */
    val simReason: String = "",
) {
    enum class Kind { PHONE_SIM, USB_READER }

    /** The card's part: in use, in but switched off or locked, ejected by the user, taken out, or hidden from VowiDroid. */
    enum class SimStatus { IN_USE, OFF, EJECTED, REMOVED, UNAVAILABLE }

    /** Calls and texts can go through this line now. */
    val usable: Boolean get() = registered
}

/** A SIM card, or a reader, VowiDroid found but has not added. */
data class DiscoveredLine(
    /** Where it is: "android-sim:slot2" for a phone slot, "usb:<device>" for a reader. */
    val usbPath: String,
    val name: String,
    /** Why it cannot be added yet, or empty. */
    val issue: String,
    /** The subscriber, when VowiDroid could read the card. */
    val imsi: String = "",
    val iccid: String = "",
    val kind: Line.Kind = Line.Kind.PHONE_SIM,
    /** The slot or reader, for the list, e.g. "SIM slot 1". */
    val place: String = "",
)

data class Conversation(
    val deviceId: String,
    val deviceName: String,
    val peer: String,
    val lastMessage: String,
    val lastTime: Instant?,
    val outgoing: Boolean,
    /** Unread texts, or 1 when the user marked the conversation unread. */
    val unread: Int,
    val archived: Boolean = false,
    val pinned: Boolean = false,
    /** Notifications are off for this conversation. */
    val muted: Boolean = false,
    /** The newest text's status, to show "Sending…" or "Not sent" in the list. */
    val lastStatus: String = "",
)

data class Message(
    val id: Long,
    val deviceId: String,
    val peer: String,
    val outgoing: Boolean,
    val body: String,
    val time: Instant?,
    val status: String,
    val deliveryState: String,
    /** Why a failed text failed: SmsStore.ERROR_RP, ERROR_SIP or ERROR_LOCAL plus the detail; empty otherwise. */
    val error: String = "",
)

data class CallRecord(
    val id: Long,
    val deviceId: String,
    val number: String,
    val outgoing: Boolean,
    val outcome: Outcome,
    val startedAt: Instant,
    val durationSeconds: Long,
) {
    enum class Outcome { ANSWERED, MISSED, REJECTED, CANCELLED, FAILED, BLOCKED }
}

data class Contact(
    val id: Long,
    val name: String,
    val numbers: List<String>,
    val photoUri: String?,
    /** Each number's type, e.g. "Mobile" or "Work", in the order of [numbers]. */
    val labels: List<String> = emptyList(),
    /** Starred in Contacts: shown among the favorites. */
    val starred: Boolean = false,
    val lookupKey: String? = null,
    /** The Contacts app's index letter for this name, e.g. "F" for 付 (pinyin fù). */
    val section: String = "",
)
