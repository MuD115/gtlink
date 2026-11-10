package com.nmnd.gtlink.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nmnd.gtlink.link.Commands
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DayTotals(val day: LocalDate, val steps: Int, val calories: Int, val distanceMeters: Int)
data class TimedValue(val timeSec: Int, val value: Int)

/**
 * Activity history downloaded from the watch: one row per minute, plus sleep segments.
 * Plain SQLite rather than Room: two tables, append-only, queried by time range.
 */
class HistoryDb(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE minute (ts INTEGER PRIMARY KEY, steps INTEGER, calories INTEGER, distance INTEGER, " +
                "hr INTEGER, spo2 INTEGER, resting INTEGER)",
        )
        db.execSQL("CREATE TABLE sleep (start INTEGER PRIMARY KEY, end INTEGER NOT NULL, type INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insertMinutes(samples: List<Commands.MinuteSample>) {
        if (samples.isEmpty()) return
        writableDatabase.run {
            beginTransaction()
            try {
                for (s in samples) {
                    insertWithOnConflict(
                        "minute", null,
                        ContentValues().apply {
                            put("ts", s.timeSec)
                            put("steps", s.steps)
                            put("calories", s.calories)
                            put("distance", s.distance)
                            put("hr", s.heartRate)
                            put("spo2", s.spo2)
                            put("resting", s.restingHeartRate)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    fun insertSleep(segments: List<Commands.SleepSegment>) {
        if (segments.isEmpty()) return
        writableDatabase.run {
            beginTransaction()
            try {
                for (s in segments) {
                    insertWithOnConflict(
                        "sleep", null,
                        ContentValues().apply { put("start", s.startSec); put("end", s.endSec); put("type", s.type) },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    fun lastMinute(): Int? = scalar("SELECT MAX(ts) FROM minute")
    fun lastSleep(): Int? = scalar("SELECT MAX(end) FROM sleep")

    private fun scalar(sql: String): Int? =
        readableDatabase.rawQuery(sql, null).use { if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) else null }

    /** Steps/calories/distance per local calendar day, oldest first, including empty days. */
    fun dailyTotals(days: Int, zone: ZoneId = ZoneId.systemDefault()): List<DayTotals> {
        val today = LocalDate.now(zone)
        val first = today.minusDays((days - 1).toLong())
        val from = first.atStartOfDay(zone).toEpochSecond()
        val totals = LinkedHashMap<LocalDate, IntArray>()
        for (i in 0 until days) totals[first.plusDays(i.toLong())] = IntArray(3)
        readableDatabase.rawQuery(
            "SELECT ts, steps, calories, distance FROM minute WHERE ts >= ?", arrayOf(from.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val day = Instant.ofEpochSecond(c.getLong(0)).atZone(zone).toLocalDate()
                val t = totals[day] ?: continue
                t[0] += c.getInt(1)
                t[1] += c.getInt(2)
                t[2] += c.getInt(3)
            }
        }
        return totals.map { (d, t) -> DayTotals(d, t[0], t[1], t[2]) }
    }

    fun heartRate(fromSec: Long, toSec: Long): List<TimedValue> = series("hr", fromSec, toSec)
    fun spo2(fromSec: Long, toSec: Long): List<TimedValue> = series("spo2", fromSec, toSec)
    fun restingHeartRate(fromSec: Long, toSec: Long): List<TimedValue> = series("resting", fromSec, toSec)

    private fun series(column: String, fromSec: Long, toSec: Long): List<TimedValue> {
        val out = mutableListOf<TimedValue>()
        readableDatabase.rawQuery(
            "SELECT ts, $column FROM minute WHERE ts >= ? AND ts < ? AND $column IS NOT NULL AND $column > 0 ORDER BY ts",
            arrayOf(fromSec.toString(), toSec.toString()),
        ).use { c -> while (c.moveToNext()) out += TimedValue(c.getInt(0), c.getInt(1)) }
        return out
    }

    fun sleep(fromSec: Long, toSec: Long): List<Commands.SleepSegment> {
        val out = mutableListOf<Commands.SleepSegment>()
        readableDatabase.rawQuery(
            "SELECT start, end, type FROM sleep WHERE end > ? AND start < ? ORDER BY start",
            arrayOf(fromSec.toString(), toSec.toString()),
        ).use { c -> while (c.moveToNext()) out += Commands.SleepSegment(c.getInt(0), c.getInt(1), c.getInt(2)) }
        return out
    }

    /** Minute rows as CSV, for export. */
    fun exportCsv(): String = buildString {
        appendLine("timestamp,steps,calories,distance_m,heart_rate,spo2,resting_hr")
        readableDatabase.rawQuery("SELECT ts, steps, calories, distance, hr, spo2, resting FROM minute ORDER BY ts", null).use { c ->
            while (c.moveToNext()) {
                append(Instant.ofEpochSecond(c.getLong(0)))
                for (i in 1..6) { append(','); if (!c.isNull(i)) append(c.getInt(i)) }
                appendLine()
            }
        }
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM minute")
        writableDatabase.execSQL("DELETE FROM sleep")
    }
}
