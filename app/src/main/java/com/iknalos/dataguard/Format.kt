package com.iknalos.dataguard

import java.util.Locale

object Format {
    fun bytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / 1e9)
        bytes >= 1_000_000L -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
    }
}
