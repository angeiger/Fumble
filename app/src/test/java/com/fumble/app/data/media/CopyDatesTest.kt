package com.fumble.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class CopyDatesTest {

    private val berlin = TimeZone.getTimeZone("Europe/Berlin")

    /** 15 May 2023, 17:42 UTC: the date of the WhatsApp picture used in testing. */
    private val summer = 1_684_172_520_000L

    /** 15 January 2023, 12:00 UTC. */
    private val winter = 1_673_784_000_000L

    @Test
    fun `writes the local date and time of the original`() {
        assertEquals("2023:05:15 19:42:00", CopyDates.exifDateTime(summer, berlin))
    }

    @Test
    fun `offset follows daylight saving time`() {
        assertEquals("+02:00", CopyDates.exifOffset(summer, berlin))
        assertEquals("+01:00", CopyDates.exifOffset(winter, berlin))
    }

    @Test
    fun `offset handles negative and non-hour zones`() {
        assertEquals("-03:30", CopyDates.exifOffset(winter, TimeZone.getTimeZone("America/St_Johns")))
        assertEquals("+05:45", CopyDates.exifOffset(winter, TimeZone.getTimeZone("Asia/Kathmandu")))
        assertEquals("+00:00", CopyDates.exifOffset(winter, TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun `a written date reads back as the same instant`() {
        val written = CopyDates.exifDateTime(summer, berlin)
        val offset = CopyDates.exifOffset(summer, berlin)
        assertEquals(summer, CopyDates.parseExif(written, offset, berlin))
        assertEquals(summer, CopyDates.parseExif(written, null, berlin))
    }

    /** The offset in the file wins over whatever zone the phone is in now. */
    @Test
    fun `an explicit offset overrides the device zone`() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        assertEquals(summer, CopyDates.parseExif("2023:05:15 19:42:00", "+02:00", tokyo))
    }

    @Test
    fun `unreadable dates are rejected rather than guessed`() {
        assertNull(CopyDates.parseExif(null, null, berlin))
        assertNull(CopyDates.parseExif("", null, berlin))
        assertNull(CopyDates.parseExif("15.05.2023 19:42", null, berlin))
    }

    /** A copy made by 4.1.1: file time today, original from 2023. */
    @Test
    fun `a copy dated today needs backdating`() {
        val copiedAtSeconds = 1_790_259_170L
        assertTrue(CopyDates.needsBackdating(copiedAtSeconds, summer))
    }

    @Test
    fun `a copy already carrying its date is left alone`() {
        assertFalse(CopyDates.needsBackdating(summer / 1000, summer))
        assertFalse(CopyDates.needsBackdating(summer / 1000 + 30, summer))
    }
}
