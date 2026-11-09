package com.haebi.petworld.ui.theme

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    fun shouldUseDark(systemIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemIsDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromStoredValue(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
