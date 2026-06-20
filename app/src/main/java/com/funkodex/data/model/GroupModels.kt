package com.funkodex.data.model

/**
 * Series-completion grouping enums.
 *
 * A figure can belong to two kinds of group: a FRANCHISE/property group (the
 * level the collector thinks in — "Hocus Pocus", "Harry Potter") and a named
 * SET group ("Haunted Mansion Mini Vinyl Figures"). Each group carries an
 * intent that decides whether its missing members auto-populate the want list.
 *
 * MIT License — Copyright (c) 2026 Chris Ahrendt
 */

/** Whether the user is completing a group or just cherry-picking from it. */
enum class GroupIntent {
    /** Auto-want the group's missing figures. Default when no preference is set. */
    COMPLETE,

    /** Show the group's fraction for information, but auto-want nothing. */
    CHERRY_PICK,
    ;

    companion object {
        /** Parse a stored intent name; unknown / null falls back to COMPLETE. */
        fun fromName(name: String?): GroupIntent =
            runCatching { name?.let { valueOf(it) } }.getOrNull() ?: COMPLETE
    }
}

/** The grouping level a [GroupIntent] applies to. Keeps the two namespaces
 *  ("Hocus Pocus" as a franchise vs. as a set) from colliding. */
enum class GroupLevel {
    FRANCHISE,
    SET,
    ;

    companion object {
        fun fromName(name: String?): GroupLevel? =
            runCatching { name?.let { valueOf(it) } }.getOrNull()
    }
}
