package com.example.mtga.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * IPC bridge so the Truth Social process can read MTGA settings without
 * relying on LSPosed's MODE_WORLD_READABLE prefs mirroring, which silently
 * fails when LSPosed has the MTGA package on its denylist (the
 * `getSharedPreferences` hook never runs and the redirect to LSPosed's
 * managed prefs dir doesn't happen).
 *
 * Exposes MTGA's `SharedPreferences` through a [ContentProvider] running in
 * the MTGA process. Truth Social's hooks call
 * `ContentResolver.query("content://com.example.mtga.settings/all")` and
 * receive a single-row cursor with one column per setting key. Values
 * serialize as strings (`"true"`/`"false"` for booleans, raw string
 * otherwise).
 *
 * Read-only: no `insert` / `update` / `delete` surface. Writes happen
 * inside [SettingsActivity] via direct `SharedPreferences.Editor`.
 */
class SettingsContentProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.example.mtga.settings"
        const val PATH_ALL = "all"

        private const val CODE_ALL = 1

        val URI_ALL: Uri = Uri.parse("content://$AUTHORITY/$PATH_ALL")
    }

    private val matcher =
        UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_ALL, CODE_ALL)
        }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (matcher.match(uri) != CODE_ALL) return null
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val all = prefs.all

        val keys = projection?.toList() ?: all.keys.toList().sorted()
        val cursor = MatrixCursor(keys.toTypedArray())
        val row =
            keys.map { key ->
                when (val v = all[key]) {
                    null -> null
                    is String -> v
                    is Boolean -> if (v) "true" else "false"
                    is Int -> v.toString()
                    is Long -> v.toString()
                    is Float -> v.toString()
                    else -> v.toString()
                }
            }
        cursor.addRow(row.toTypedArray())
        return cursor
    }

    override fun getType(uri: Uri): String? =
        when (matcher.match(uri)) {
            CODE_ALL -> "vnd.android.cursor.item/vnd.com.example.mtga.settings"
            else -> null
        }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
