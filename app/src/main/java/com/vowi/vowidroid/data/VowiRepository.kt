package com.vowi.vowidroid.data

import com.vowi.vowidroid.VowiCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The app's view of VowiDroid: lines, conversations and messages, straight from
 * the native core. Screens observe the flows; commands run off the main thread.
 */
object VowiRepository {
    val lines: StateFlow<List<Line>> get() = VowiCore.lines
    val conversations: StateFlow<List<Conversation>> get() = VowiCore.conversations

    /** Emits whenever messages may have changed, e.g. a text arrived. */
    val messageChanges: SharedFlow<Unit> get() = VowiCore.messageChanges

    fun refreshAll() {
        VowiCore.refreshLines()
        VowiCore.refreshConversations()
    }

    suspend fun refreshLines() = withContext(Dispatchers.IO) { VowiCore.refreshLines() }

    suspend fun refreshConversations() = withContext(Dispatchers.IO) { VowiCore.refreshConversations() }

    /** Loads a conversation, oldest first; its inbound texts are marked read. */
    suspend fun thread(deviceId: String, peer: String): List<Message> = withContext(Dispatchers.IO) { VowiCore.thread(deviceId, peer) }

    suspend fun sendSms(deviceId: String, peer: String, text: String) = withContext(Dispatchers.IO) { VowiCore.sendSms(deviceId, peer, text) }

    suspend fun deleteThread(deviceId: String, peer: String) = withContext(Dispatchers.IO) { VowiCore.deleteThread(deviceId, peer) }

    suspend fun deleteMessage(id: Long) = withContext(Dispatchers.IO) { VowiCore.deleteMessage(id) }

    suspend fun resendSms(id: Long) = withContext(Dispatchers.IO) { VowiCore.resendSms(id) }

    suspend fun markRead(deviceId: String, peer: String) = withContext(Dispatchers.IO) { VowiCore.markRead(deviceId, peer) }

    suspend fun markUnread(deviceId: String, peer: String) = withContext(Dispatchers.IO) { VowiCore.markUnread(deviceId, peer) }

    suspend fun setArchived(deviceId: String, peer: String, archived: Boolean) = withContext(Dispatchers.IO) { VowiCore.setArchived(deviceId, peer, archived) }

    suspend fun setPinned(deviceId: String, peer: String, pinned: Boolean) = withContext(Dispatchers.IO) { VowiCore.setPinned(deviceId, peer, pinned) }

    suspend fun setMuted(deviceId: String, peer: String, muted: Boolean) = withContext(Dispatchers.IO) { VowiCore.setMuted(deviceId, peer, muted) }

    suspend fun searchMessages(text: String): List<Message> = withContext(Dispatchers.IO) { VowiCore.searchMessages(text) }

    suspend fun voicemailNumber(lineId: String): String? = withContext(Dispatchers.IO) { VowiCore.voicemailNumber(lineId) }

    suspend fun voicemailNumberOrAccount(lineId: String): String? = withContext(Dispatchers.IO) { VowiCore.voicemailNumberOrAccount(lineId) }

    val voicemail = VowiCore.voicemail

    suspend fun ussd(lineId: String, code: String) = withContext(Dispatchers.IO) { VowiCore.ussd(lineId, code) }

    suspend fun ussdReply(lineId: String, text: String) = withContext(Dispatchers.IO) { VowiCore.ussdReply(lineId, text) }

    suspend fun ussdCancel(lineId: String) = withContext(Dispatchers.IO) { VowiCore.ussdCancel(lineId) }

    /** The carrier's word on Wi-Fi calling and the emergency address for a line. */
    suspend fun emergencyAddress(lineId: String, freshSignIn: Boolean = false) = withContext(Dispatchers.IO) { VowiCore.emergencyAddress(lineId, freshSignIn) }

    suspend fun setWifiCalling(deviceId: String, enabled: Boolean) = withContext(Dispatchers.IO) { VowiCore.setWifiCalling(deviceId, enabled) }

    suspend fun reconnect(deviceId: String) = withContext(Dispatchers.IO) { VowiCore.reconnect(deviceId) }

    suspend fun discovered(): List<DiscoveredLine> = withContext(Dispatchers.IO) { VowiCore.discovered() }

    /** Adds a discovered line; its WiFi Calling turns on right away. */
    suspend fun addLine(line: DiscoveredLine, existingLineId: String? = null) = withContext(Dispatchers.IO) { VowiCore.addLine(line, existingLineId) }

    fun linesWithoutCard(): List<Line> = VowiCore.linesWithoutCard()

    suspend fun requestReaderAccess(usbPath: String) = withContext(Dispatchers.IO) { VowiCore.requestReaderAccess(usbPath) }

    suspend fun ejectLine(id: String) = withContext(Dispatchers.IO) { VowiCore.ejectLine(id) }

    suspend fun resumeLine(id: String) = withContext(Dispatchers.IO) { VowiCore.resumeLine(id) }

    suspend fun renameLine(id: String, name: String) = withContext(Dispatchers.IO) { VowiCore.renameLine(id, name) }

    suspend fun removeLine(deviceId: String) = withContext(Dispatchers.IO) { VowiCore.removeLine(deviceId) }
}
