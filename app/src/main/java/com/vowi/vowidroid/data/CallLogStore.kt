package com.vowi.vowidroid.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** VowiDroid's own call history; VoIP calls do not belong in the phone's call log. */
object CallLogStore {
    private const val TABLE = "calls"
    private var helper: SQLiteOpenHelper? = null
    private val mutableCalls = MutableStateFlow<List<CallRecord>>(emptyList())
    val calls: StateFlow<List<CallRecord>> = mutableCalls.asStateFlow()

    fun init(context: Context) {
        if (helper != null) return
        helper = object : SQLiteOpenHelper(context.applicationContext, "calls.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE $TABLE (id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT NOT NULL, " +
                        "number TEXT NOT NULL, outgoing INTEGER NOT NULL, outcome TEXT NOT NULL, " +
                        "started_at INTEGER NOT NULL, duration INTEGER NOT NULL)",
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        reload()
    }

    @Synchronized
    fun add(record: CallRecord) {
        val db = helper?.writableDatabase ?: return
        db.insert(
            TABLE,
            null,
            ContentValues().apply {
                put("device_id", record.deviceId)
                put("number", record.number)
                put("outgoing", if (record.outgoing) 1 else 0)
                put("outcome", record.outcome.name)
                put("started_at", record.startedAt.toEpochMilli())
                put("duration", record.durationSeconds)
            },
        )
        reload()
    }

    @Synchronized
    fun delete(id: Long) {
        helper?.writableDatabase?.delete(TABLE, "id = ?", arrayOf(id.toString()))
        reload()
    }

    /** Removes several calls at once, e.g. a grouped row. */
    @Synchronized
    fun delete(ids: List<Long>) {
        val db = helper?.writableDatabase ?: return
        db.beginTransaction()
        try {
            ids.forEach { db.delete(TABLE, "id = ?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        reload()
    }

    /** Removes every call with [number], as Delete on AOSP's call details does. */
    @Synchronized
    fun deleteNumber(number: String) {
        helper?.writableDatabase?.delete(TABLE, "number = ?", arrayOf(number))
        reload()
    }

    @Synchronized
    fun clear() {
        helper?.writableDatabase?.delete(TABLE, null, null)
        reload()
    }

    /** Every line that has calls here. */
    @Synchronized
    fun deviceIds(): List<String> {
        val db = helper?.readableDatabase ?: return emptyList()
        return db.rawQuery("SELECT DISTINCT device_id FROM $TABLE", null).use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toList()
        }
    }

    /** Moves one line's calls to another line, e.g. the same SIM added again. */
    @Synchronized
    fun reassignDevice(from: String, to: String) {
        helper?.writableDatabase?.execSQL("UPDATE $TABLE SET device_id = ? WHERE device_id = ?", arrayOf(to, from))
        reload()
    }

    @Synchronized
    private fun reload() {
        val db = helper?.readableDatabase ?: return
        val records = mutableListOf<CallRecord>()
        db.query(TABLE, null, null, null, null, null, "started_at DESC", "500").use { cursor ->
            while (cursor.moveToNext()) {
                records += CallRecord(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    deviceId = cursor.getString(cursor.getColumnIndexOrThrow("device_id")),
                    number = cursor.getString(cursor.getColumnIndexOrThrow("number")),
                    outgoing = cursor.getInt(cursor.getColumnIndexOrThrow("outgoing")) == 1,
                    outcome = runCatching {
                        CallRecord.Outcome.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("outcome")))
                    }.getOrDefault(CallRecord.Outcome.FAILED),
                    startedAt = Instant.ofEpochMilli(cursor.getLong(cursor.getColumnIndexOrThrow("started_at"))),
                    durationSeconds = cursor.getLong(cursor.getColumnIndexOrThrow("duration")),
                )
            }
        }
        mutableCalls.value = records
    }
}
