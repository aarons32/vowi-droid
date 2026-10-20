package com.vowi.vowidroid.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant

/** Texts sent and received over IMS, kept in the app's own database. */
class SmsStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "messages.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                peer TEXT NOT NULL,
                outgoing INTEGER NOT NULL,
                body TEXT NOT NULL,
                time INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT '',
                delivery_state TEXT NOT NULL DEFAULT '',
                read INTEGER NOT NULL DEFAULT 1,
                message_key TEXT UNIQUE,
                pending_refs TEXT NOT NULL DEFAULT '',
                error TEXT NOT NULL DEFAULT ''
            )""",
        )
        db.execSQL("CREATE INDEX messages_thread ON messages(device_id, peer, time)")
        createSettings(db)
    }

    companion object {
        /** How a failed text's error names its source: the service centre's RP-Cause, the IMS core's SIP code, or VowiDroid itself. */
        const val ERROR_RP = "rp:"
        const val ERROR_SIP = "sip:"
        const val ERROR_LOCAL = "local:"
    }

    /** Per-conversation choices: archived, pinned, muted, marked unread. */
    private fun createSettings(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS conversation_settings (
                device_id TEXT NOT NULL,
                peer TEXT NOT NULL,
                archived INTEGER NOT NULL DEFAULT 0,
                pinned INTEGER NOT NULL DEFAULT 0,
                muted INTEGER NOT NULL DEFAULT 0,
                marked_unread INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (device_id, peer)
            )""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 2: the TP-MRs a sent text still waits on, to match status reports to it.
        if (oldVersion < 2) db.execSQL("ALTER TABLE messages ADD COLUMN pending_refs TEXT NOT NULL DEFAULT ''")
        // Version 3: archive, pin, mute and mark-unread per conversation.
        if (oldVersion < 3) createSettings(db)
        // Version 4: why a text failed, as ERROR_RP, ERROR_SIP or ERROR_LOCAL plus the detail.
        if (oldVersion < 4) db.execSQL("ALTER TABLE messages ADD COLUMN error TEXT NOT NULL DEFAULT ''")
    }

    /** Stores an inbound text; a repeated delivery of the same message is ignored. */
    fun addInbound(deviceId: String, peer: String, body: String, time: Long, key: String): Boolean {
        val values = ContentValues().apply {
            put("device_id", deviceId)
            put("peer", peer)
            put("outgoing", 0)
            put("body", body)
            put("time", time)
            put("status", "received")
            put("read", 0)
            put("message_key", key)
        }
        return writableDatabase.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE) > 0
    }

    fun addOutbound(deviceId: String, peer: String, body: String, status: String): Long {
        val values = ContentValues().apply {
            put("device_id", deviceId)
            put("peer", peer)
            put("outgoing", 1)
            put("body", body)
            put("time", System.currentTimeMillis())
            put("status", status)
            put("read", 1)
        }
        return writableDatabase.insert("messages", null, values)
    }

    fun setStatus(id: Long, status: String, deliveryState: String? = null, error: String? = null) {
        val values = ContentValues().apply {
            put("status", status)
            if (deliveryState != null) put("delivery_state", deliveryState)
            if (error != null) put("error", error)
        }
        writableDatabase.update("messages", values, "id = ?", arrayOf(id.toString()))
    }

    /** Records a text the network accepted, and the TP-MR of each part, which status reports will name. */
    fun setSent(id: Long, references: List<Int>) {
        val values = ContentValues().apply {
            put("status", "sent")
            put("delivery_state", "")
            put("error", "")
            put("pending_refs", DeliveryReports.format(references))
        }
        writableDatabase.update("messages", values, "id = ?", arrayOf(id.toString()))
    }

    /** Puts a failed text back to "sending", now, for a resend. */
    fun resetForResend(id: Long) {
        val values = ContentValues().apply {
            put("status", "sending")
            put("delivery_state", "")
            put("error", "")
            put("pending_refs", "")
            put("time", System.currentTimeMillis())
        }
        writableDatabase.update("messages", values, "id = ?", arrayOf(id.toString()))
    }

    /**
     * Marks the sent text a part of which the service centre refused, found by
     * the reference it went out with among the line's recent sent texts; the
     * text goes back to failed, with [error] as the reason, for a resend.
     */
    fun failSubmission(deviceId: String, reference: Int, error: String): Boolean {
        var match: Long? = null
        readableDatabase.query(
            "messages", arrayOf("id", "pending_refs"),
            "device_id = ? AND outgoing = 1 AND pending_refs != ''", arrayOf(deviceId),
            null, null, "time DESC, id DESC", "50",
        ).use { cursor ->
            while (cursor.moveToNext() && match == null) {
                if (reference in DeliveryReports.parse(cursor.getString(1))) match = cursor.getLong(0)
            }
        }
        val id = match ?: return false
        val values = ContentValues().apply {
            put("status", "failed")
            put("delivery_state", "")
            put("pending_refs", "")
            put("error", error)
        }
        writableDatabase.update("messages", values, "id = ?", arrayOf(id.toString()))
        return true
    }

    /**
     * Applies a status report to the text it belongs to, found by its TP-MR among
     * the line's recent sent texts. Returns false when no text waits for it.
     */
    fun applyStatusReport(deviceId: String, to: String, reference: Int, state: String): Boolean {
        class Candidate(val id: Long, val peer: String, val pending: String)
        val candidates = ArrayList<Candidate>()
        readableDatabase.query(
            "messages", arrayOf("id", "peer", "pending_refs"),
            "device_id = ? AND outgoing = 1 AND pending_refs != ''", arrayOf(deviceId),
            null, null, "time DESC, id DESC", "50",
        ).use { cursor ->
            while (cursor.moveToNext()) candidates.add(Candidate(cursor.getLong(0), cursor.getString(1), cursor.getString(2)))
        }
        val waiting = candidates.filter { DeliveryReports.apply(it.pending, reference, state) != null }
        val match = waiting.firstOrNull { DeliveryReports.samePeer(it.peer, to) } ?: waiting.firstOrNull() ?: return false
        val update = DeliveryReports.apply(match.pending, reference, state) ?: return false
        val values = ContentValues().apply {
            put("pending_refs", update.pendingRefs)
            update.deliveryState?.let { put("delivery_state", it) }
        }
        writableDatabase.update("messages", values, "id = ?", arrayOf(match.id.toString()))
        return true
    }

    /** The conversation, oldest first; its inbound texts are marked read. */
    fun thread(deviceId: String, peer: String, limit: Int = 500): List<Message> {
        val messages = query("device_id = ? AND peer = ?", arrayOf(deviceId, peer), limit)
        markRead(deviceId, peer)
        return messages.reversed()
    }

    /** One text, or null when it is gone. */
    fun message(id: Long): Message? = query("id = ?", arrayOf(id.toString()), 1).firstOrNull()

    /** Unread texts from [peer], oldest first, for the notification. */
    fun unread(deviceId: String, peer: String, limit: Int = 6): List<Message> =
        query("device_id = ? AND peer = ? AND outgoing = 0 AND read = 0", arrayOf(deviceId, peer), limit).reversed()

    fun markRead(deviceId: String, peer: String) {
        writableDatabase.execSQL("UPDATE messages SET read = 1 WHERE device_id = ? AND peer = ? AND read = 0", arrayOf(deviceId, peer))
        writableDatabase.execSQL("UPDATE conversation_settings SET marked_unread = 0 WHERE device_id = ? AND peer = ?", arrayOf(deviceId, peer))
    }

    class Settings(val archived: Boolean, val pinned: Boolean, val muted: Boolean, val markedUnread: Boolean)

    fun settings(deviceId: String, peer: String): Settings {
        readableDatabase.rawQuery(
            "SELECT archived, pinned, muted, marked_unread FROM conversation_settings WHERE device_id = ? AND peer = ?",
            arrayOf(deviceId, peer),
        ).use { cursor ->
            if (cursor.moveToFirst()) return Settings(cursor.getInt(0) != 0, cursor.getInt(1) != 0, cursor.getInt(2) != 0, cursor.getInt(3) != 0)
        }
        return Settings(archived = false, pinned = false, muted = false, markedUnread = false)
    }

    fun setArchived(deviceId: String, peer: String, value: Boolean) = setFlag(deviceId, peer, "archived", value)

    fun setPinned(deviceId: String, peer: String, value: Boolean) = setFlag(deviceId, peer, "pinned", value)

    fun setMuted(deviceId: String, peer: String, value: Boolean) = setFlag(deviceId, peer, "muted", value)

    fun setMarkedUnread(deviceId: String, peer: String, value: Boolean) = setFlag(deviceId, peer, "marked_unread", value)

    private fun setFlag(deviceId: String, peer: String, column: String, value: Boolean) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO conversation_settings (device_id, peer) VALUES (?, ?)", arrayOf(deviceId, peer))
        db.execSQL("UPDATE conversation_settings SET $column = ? WHERE device_id = ? AND peer = ?", arrayOf(if (value) 1 else 0, deviceId, peer))
    }

    /** Texts containing [text], newest first. */
    fun search(text: String, limit: Int = 50): List<Message> {
        val escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return query("body LIKE ? ESCAPE '\\'", arrayOf("%$escaped%"), limit)
    }

    fun deleteMessage(id: Long) {
        writableDatabase.delete("messages", "id = ?", arrayOf(id.toString()))
    }

    /** Matching texts, newest first. */
    private fun query(selection: String, arguments: Array<String>, limit: Int): List<Message> {
        val messages = ArrayList<Message>()
        readableDatabase.query("messages", null, selection, arguments, null, null, "time DESC, id DESC", limit.toString()).use { cursor ->
            while (cursor.moveToNext()) {
                messages.add(
                    Message(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        deviceId = cursor.getString(cursor.getColumnIndexOrThrow("device_id")),
                        peer = cursor.getString(cursor.getColumnIndexOrThrow("peer")),
                        outgoing = cursor.getInt(cursor.getColumnIndexOrThrow("outgoing")) != 0,
                        body = cursor.getString(cursor.getColumnIndexOrThrow("body")),
                        time = Instant.ofEpochMilli(cursor.getLong(cursor.getColumnIndexOrThrow("time"))),
                        status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                        deliveryState = cursor.getString(cursor.getColumnIndexOrThrow("delivery_state")),
                        error = cursor.getString(cursor.getColumnIndexOrThrow("error")),
                    ),
                )
            }
        }
        return messages
    }

    /** Every conversation with its newest text: pinned ones first, then the newest. */
    fun conversations(limit: Int = 500): List<Conversation> {
        val result = ArrayList<Conversation>()
        readableDatabase.rawQuery(
            """SELECT m.device_id, m.peer, m.body, m.time, m.outgoing,
                      (SELECT COUNT(*) FROM messages u WHERE u.device_id = m.device_id AND u.peer = m.peer AND u.read = 0) AS unread,
                      COALESCE(s.archived, 0), COALESCE(s.pinned, 0), COALESCE(s.muted, 0), COALESCE(s.marked_unread, 0), m.status
               FROM messages m
               LEFT JOIN conversation_settings s ON s.device_id = m.device_id AND s.peer = m.peer
               WHERE m.id IN (SELECT MAX(id) FROM messages GROUP BY device_id, peer)
               ORDER BY COALESCE(s.pinned, 0) DESC, m.time DESC LIMIT ?""",
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val unread = cursor.getInt(5)
                val markedUnread = cursor.getInt(9) != 0
                result.add(
                    Conversation(
                        deviceId = cursor.getString(0),
                        deviceName = "",
                        peer = cursor.getString(1),
                        lastMessage = cursor.getString(2),
                        lastTime = Instant.ofEpochMilli(cursor.getLong(3)),
                        outgoing = cursor.getInt(4) != 0,
                        unread = if (markedUnread) maxOf(unread, 1) else unread,
                        archived = cursor.getInt(6) != 0,
                        pinned = cursor.getInt(7) != 0,
                        muted = cursor.getInt(8) != 0,
                        lastStatus = cursor.getString(10),
                    ),
                )
            }
        }
        return result
    }

    /** Every line that has texts here. */
    fun deviceIds(): List<String> = readableDatabase.rawQuery("SELECT DISTINCT device_id FROM messages", null).use { cursor ->
        generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toList()
    }

    /** Moves one line's texts and conversation settings to another line, e.g. the same SIM added again. */
    fun reassignDevice(from: String, to: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE messages SET device_id = ? WHERE device_id = ?", arrayOf(to, from))
            // A conversation the new line already has keeps its own settings.
            db.execSQL("UPDATE OR IGNORE conversation_settings SET device_id = ? WHERE device_id = ?", arrayOf(to, from))
            db.execSQL("DELETE FROM conversation_settings WHERE device_id = ?", arrayOf(from))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteThread(deviceId: String, peer: String) {
        writableDatabase.delete("messages", "device_id = ? AND peer = ?", arrayOf(deviceId, peer))
        writableDatabase.delete("conversation_settings", "device_id = ? AND peer = ?", arrayOf(deviceId, peer))
    }
}
