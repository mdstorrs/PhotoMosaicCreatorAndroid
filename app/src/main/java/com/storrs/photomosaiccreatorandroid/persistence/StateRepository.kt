package com.storrs.photomosaiccreatorandroid.persistence

import android.content.Context
import android.content.SharedPreferences
import com.storrs.photomosaiccreatorandroid.models.PatternKind
import org.json.JSONArray

/**
 * Persists and restores the last-used mosaic settings so the user
 * can resume where they left off after closing the app.
 */
class StateRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Primary image ───────────────────────────────────────────────

    fun savePrimaryImagePath(path: String?) {
        prefs.edit().putString(KEY_PRIMARY_IMAGE, path).apply()
    }

    fun loadPrimaryImagePath(): String? {
        return prefs.getString(KEY_PRIMARY_IMAGE, null)
    }

    // ── Cell photos ─────────────────────────────────────────────────

    fun saveCellPhotoPaths(paths: List<String>) {
        val json = JSONArray(paths).toString()
        prefs.edit().putString(KEY_CELL_PHOTOS, json).apply()
    }

    fun loadCellPhotoPaths(): List<String> {
        val json = prefs.getString(KEY_CELL_PHOTOS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Print size ──────────────────────────────────────────────────

    fun savePrintSizeLabel(label: String) {
        prefs.edit().putString(KEY_PRINT_SIZE, label).apply()
    }

    fun loadPrintSizeLabel(): String? {
        return prefs.getString(KEY_PRINT_SIZE, null)
    }

    // ── Cell size ───────────────────────────────────────────────────

    fun saveCellSizeLabel(label: String) {
        prefs.edit().putString(KEY_CELL_SIZE, label).apply()
    }

    fun loadCellSizeLabel(): String? {
        return prefs.getString(KEY_CELL_SIZE, null)
    }

    // ── Color change ────────────────────────────────────────────────

    fun saveColorChangePercent(percent: Int) {
        prefs.edit().putInt(KEY_COLOR_CHANGE, percent).apply()
    }

    fun loadColorChangePercent(): Int {
        return prefs.getInt(KEY_COLOR_CHANGE, -1)
    }

    // ── Pattern ─────────────────────────────────────────────────────

    fun savePattern(kind: PatternKind) {
        prefs.edit().putString(KEY_PATTERN, kind.name).apply()
    }

    fun loadPattern(): PatternKind? {
        val name = prefs.getString(KEY_PATTERN, null) ?: return null
        return try {
            PatternKind.valueOf(name)
        } catch (_: Exception) {
            null
        }
    }

    // ── Use all images ──────────────────────────────────────────────

    fun saveUseAllImages(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_ALL_IMAGES, enabled).apply()
    }

    fun loadUseAllImages(): Boolean? {
        return if (prefs.contains(KEY_USE_ALL_IMAGES)) prefs.getBoolean(KEY_USE_ALL_IMAGES, true) else null
    }

    // ── Mirror images ───────────────────────────────────────────────

    fun saveMirrorImages(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MIRROR_IMAGES, enabled).apply()
    }

    fun loadMirrorImages(): Boolean? {
        return if (prefs.contains(KEY_MIRROR_IMAGES)) prefs.getBoolean(KEY_MIRROR_IMAGES, true) else null
    }

    companion object {
        private const val PREFS_NAME = "mosaic_state"
        private const val KEY_PRIMARY_IMAGE = "primary_image_path"
        private const val KEY_CELL_PHOTOS = "cell_photo_paths"
        private const val KEY_PRINT_SIZE = "print_size_label"
        private const val KEY_CELL_SIZE = "cell_size_label"
        private const val KEY_COLOR_CHANGE = "color_change_percent"
        private const val KEY_PATTERN = "pattern_kind"
        private const val KEY_USE_ALL_IMAGES = "use_all_images"
        private const val KEY_MIRROR_IMAGES = "mirror_images"
    }
}

