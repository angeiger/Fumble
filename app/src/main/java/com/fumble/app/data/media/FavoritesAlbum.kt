package com.fumble.app.data.media

/**
 * Where favourites live, and which photos can be moved there at all.
 *
 * Kept free of Android types so the rules can be unit tested: they decide whether a
 * photo is moved or copied, and getting that wrong either duplicates photos needlessly
 * or silently fails to deliver them.
 */
object FavoritesAlbum {

    const val NAME = "Fumble Favoriten"

    /**
     * Under `Pictures/`, the conventional home for images an app organises, rather than
     * `DCIM/`, which galleries treat as camera output. Spelled out rather than built
     * from `Environment.DIRECTORY_PICTURES`, whose value is fixed at "Pictures" but
     * which cannot be read in a local unit test.
     */
    const val RELATIVE_PATH = "Pictures/$NAME/"

    /** Whether a photo at [relativePath] already sits in the album. */
    fun contains(relativePath: String?): Boolean =
        relativePath != null && normalise(relativePath) == normalise(RELATIVE_PATH)

    /**
     * Whether Android will refuse to let another app move a photo out of [relativePath].
     *
     * `Android/media/<package>/` is an app's own media area — WhatsApp keeps every
     * received picture there. Android lets other apps read those files but not move
     * them out, and it refuses *silently*: the target folder is created, the update
     * reports success, and the file stays where it was. Found in real use, when 48
     * WhatsApp favourites were reported as moved into an album that stayed empty.
     *
     * Photos here are copied into the album instead, which also keeps them working in
     * the chat they came from.
     */
    fun isUnmovable(relativePath: String): Boolean =
        normalise(relativePath).startsWith("android/media/")

    private fun normalise(path: String): String =
        path.trim().trimStart('/').trimEnd('/').plus('/').lowercase()
}
