package com.fumble.app.data.media

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * How a copied favourite keeps its original date, as plain rules.
 *
 * Android does not take a copy's capture date from what the app tells it. When the copy
 * is published, the media scanner reads the file and derives the date from the file
 * alone. An EXIF date counts only if a time-zone offset is written next to it, or if the
 * file's own modification time lies within a day of it. A copy written today has
 * neither, so up to 4.1.1 copies ended up with no capture date and a modification date
 * of today. Google Photos reads the EXIF date itself and sorted them correctly; every
 * app that asks Android showed them as new.
 *
 * So a copy gets both: the EXIF date with its offset, and a file time set back to the
 * original date. The date the file was added to the device stays today. No app can
 * change that.
 */
object CopyDates {

    const val EXIF_PATTERN = "yyyy:MM:dd HH:mm:ss"

    /**
     * Closer than this, a copy's file time already matches its capture date. File
     * times are kept to the second, and a gallery sorts by day.
     */
    private const val TOLERANCE_MILLIS = 60_000L

    /** The EXIF local date and time of [takenMillis] in [zone]. */
    fun exifDateTime(takenMillis: Long, zone: TimeZone): String =
        format(zone).format(Date(takenMillis))

    /** The EXIF `OffsetTime` form of [zone] at [takenMillis], e.g. `+02:00`. */
    fun exifOffset(takenMillis: Long, zone: TimeZone): String {
        val minutes = zone.getOffset(takenMillis) / 60_000
        val sign = if (minutes < 0) '-' else '+'
        val magnitude = abs(minutes)
        return String.format(Locale.US, "%c%02d:%02d", sign, magnitude / 60, magnitude % 60)
    }

    /**
     * Reads an EXIF date back as an instant. Used when a copy's original has gone and
     * the date written into the copy is the only one left.
     *
     * @param offset the EXIF `OffsetTimeOriginal`, if the file has one. Without it the
     *   date is taken to be local time in [zone].
     */
    fun parseExif(dateTime: String?, offset: String?, zone: TimeZone): Long? {
        if (dateTime.isNullOrBlank()) return null
        val effectiveZone = offset?.let { TimeZone.getTimeZone("GMT${it.trim()}") } ?: zone
        return try {
            format(effectiveZone).parse(dateTime.trim())?.time
        } catch (e: ParseException) {
            null
        }
    }

    /** Whether a copy's file time is still far from the date it should carry. */
    fun needsBackdating(modifiedSeconds: Long, takenMillis: Long): Boolean =
        abs(modifiedSeconds * 1000 - takenMillis) > TOLERANCE_MILLIS

    private fun format(zone: TimeZone) =
        SimpleDateFormat(EXIF_PATTERN, Locale.US).apply {
            timeZone = zone
            isLenient = false
        }
}
