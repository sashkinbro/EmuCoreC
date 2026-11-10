package com.sbro.emucorec.data

import android.content.Context
import androidx.core.content.edit

/** Persists the library grid/list toggle across app restarts. */
class LibraryLayoutPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    var layoutMode: String
        get() = prefs.getString(KEY_LAYOUT_MODE, DEFAULT_LAYOUT_MODE) ?: DEFAULT_LAYOUT_MODE
        set(value) = prefs.edit { putString(KEY_LAYOUT_MODE, value) }

    companion object {
        private const val PREFERENCES_NAME = "emucorec_library_prefs"
        private const val KEY_LAYOUT_MODE = "layout_mode"
        const val DEFAULT_LAYOUT_MODE = "GRID"
    }
}
