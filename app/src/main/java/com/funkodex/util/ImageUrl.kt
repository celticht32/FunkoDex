package com.funkodex.util

/**
 * Image URL helpers.
 *
 * Funko image URLs reach the app from three sources: the bundled HobbyDB/funko.com
 * catalog (all https), and the runtime UPC lookup tiers (Channel3, UPCitemdb, etc.),
 * which sometimes return `http://` URLs from distributor image hosts (e.g.
 * media.aent-m.com). The app's network security policy blocks cleartext HTTP, so
 * Coil refuses those with "CLEARTEXT communication ... not permitted" before it
 * even attempts a load.
 *
 * Almost every image host that serves over http also serves the identical path
 * over https, so upgrading the scheme fixes the load without weakening the
 * security policy (no cleartext allow-list needed). If a host genuinely has no
 * https endpoint, the load fails cleanly through the normal error path rather
 * than being silently blocked.
 */

/**
 * Returns this URL with an `http://` scheme upgraded to `https://`. Blank values,
 * already-https URLs, and non-http schemes (data:, file:, content:) are returned
 * unchanged. Case-insensitive on the scheme only.
 */
fun String.toHttpsImageUrl(): String =
    if (regionMatches(0, "http://", 0, 7, ignoreCase = true))
        "https://" + substring(7)
    else
        this
