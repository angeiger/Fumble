package com.fumble.app.data.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesAlbumTest {

    @Test
    fun `recognises the album with or without a trailing slash`() {
        assertTrue(FavoritesAlbum.contains("Pictures/Fumble Favoriten/"))
        assertTrue(FavoritesAlbum.contains("Pictures/Fumble Favoriten"))
    }

    /** Shared storage is case-insensitive, so the check must be too. */
    @Test
    fun `recognises the album regardless of case`() {
        assertTrue(FavoritesAlbum.contains("pictures/fumble favoriten/"))
    }

    @Test
    fun `other folders are not the album`() {
        assertFalse(FavoritesAlbum.contains("DCIM/Camera/"))
        assertFalse(FavoritesAlbum.contains("Pictures/"))
        assertFalse(FavoritesAlbum.contains(null))
    }

    @Test
    fun `a folder that merely starts with the album name is not the album`() {
        assertFalse(FavoritesAlbum.contains("Pictures/Fumble Favoriten Alt/"))
    }

    /** The case found in real use: 48 favourites, none of which could be moved. */
    @Test
    fun `WhatsApp images cannot be moved`() {
        assertTrue(
            FavoritesAlbum.isUnmovable(
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"
            )
        )
    }

    @Test
    fun `any app media area counts as unmovable`() {
        assertTrue(FavoritesAlbum.isUnmovable("Android/media/org.telegram.messenger/Telegram/"))
    }

    @Test
    fun `camera and ordinary folders can be moved`() {
        assertFalse(FavoritesAlbum.isUnmovable("DCIM/Camera/"))
        assertFalse(FavoritesAlbum.isUnmovable("Pictures/Screenshots/"))
        assertFalse(FavoritesAlbum.isUnmovable("Download/"))
    }
}
