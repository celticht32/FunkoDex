package com.funkodex.util

/**
 * LogLevel — configurable log verbosity.
 *
 * DEFAULT: INFO — captures errors and warnings without overwhelming storage.
 * Set to DEBUG during development or when troubleshooting specific issues.
 * Set to WARN/ERROR for production minimal logging.
 *
 * Stored in DataStore (user_prefs) and readable from Settings > Advanced > Log level.
 */
enum class LogLevel(
    val displayName: String,
    val description: String,
    val androidPriority: Int,  // matches android.util.Log constants
) {
    VERBOSE(
        displayName = "Verbose",
        description = "Everything — very noisy. Use only when debugging a specific issue.",
        androidPriority = 2,
    ),
    DEBUG(
        displayName = "Debug",
        description = "Detailed flow information. Good for development and troubleshooting.",
        androidPriority = 3,
    ),
    INFO(
        displayName = "Info (default)",
        description = "Normal operation events. Recommended for everyday use.",
        androidPriority = 4,
    ),
    WARN(
        displayName = "Warn",
        description = "Only warnings and errors. Minimal storage use.",
        androidPriority = 5,
    ),
    ERROR(
        displayName = "Error",
        description = "Only errors. Share this log when reporting a crash.",
        androidPriority = 6,
    );

    companion object {
        val DEFAULT = INFO
        fun fromName(name: String): LogLevel =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
