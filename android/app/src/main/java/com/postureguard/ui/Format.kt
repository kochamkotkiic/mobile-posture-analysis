package com.postureguard.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 42 s -> "0:42", 754 s -> "12:34", 3 h 5 min -> "3:05:00". */
fun formatClock(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}

/** Short duration for tiles: "45 s", "12 min", "1 h 05". */
fun formatShortDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min"
        else -> String.format(Locale.US, "%d h %02d", s / 3600, (s % 3600) / 60)
    }
}

/** "today at 14:02", "yesterday at 09:15" or "24 Nov at 18:30". */
fun formatCalibrationTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    val day = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val sameYear = day.get(Calendar.YEAR) == today.get(Calendar.YEAR)
    val dayDiff = today.get(Calendar.DAY_OF_YEAR) - day.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> "today at $time"
        sameYear && dayDiff == 1 -> "yesterday at $time"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(millis)) + " at $time"
    }
}
