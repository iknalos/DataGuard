package com.iknalos.dataguard

import android.content.Context
import java.util.Calendar

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("dataguard", Context.MODE_PRIVATE)

    /** Monthly data cap in (decimal) gigabytes, matching how carriers count. */
    var capGb: Double
        get() = sp.getFloat("cap_gb", 30f).toDouble()
        set(v) = sp.edit().putFloat("cap_gb", v.toFloat()).apply()

    /** Day of month the billing cycle resets (1-31). */
    var cycleDay: Int
        get() = sp.getInt("cycle_day", 1)
        set(v) = sp.edit().putInt("cycle_day", v).apply()

    fun lastNotified(cycleKey: String): Int = sp.getInt("notified_$cycleKey", 0)
    fun setLastNotified(cycleKey: String, pct: Int) =
        sp.edit().putInt("notified_$cycleKey", pct).apply()

    companion object {
        fun todayStart(): Long {
            val c = Calendar.getInstance()
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }

        fun cycleStart(day: Int): Long {
            val c = Calendar.getInstance()
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            if (c.get(Calendar.DAY_OF_MONTH) < day) c.add(Calendar.MONTH, -1)
            c.set(Calendar.DAY_OF_MONTH, minOf(day, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
            return c.timeInMillis
        }

        fun nextCycleStart(day: Int): Long {
            val c = Calendar.getInstance()
            c.timeInMillis = cycleStart(day)
            c.add(Calendar.MONTH, 1)
            c.set(Calendar.DAY_OF_MONTH, minOf(day, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
            return c.timeInMillis
        }
    }
}
