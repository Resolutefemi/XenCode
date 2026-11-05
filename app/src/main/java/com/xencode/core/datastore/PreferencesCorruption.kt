package com.xencode.core.datastore

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.xencode.core.util.FileLogger

private const val TAG = "PreferencesCorruption"

/**
 * Shared corruption handler for all preference DataStores.
 *
 * Without a handler, [androidx.datastore.core.CorruptionException] is thrown straight to the
 * caller, and if it lands in a ViewModel/repository coroutine the whole app crashes. Worse, any
 * IOException during a read gets wrapped by protobuf into an InvalidProtocolBufferException,
 * which PreferencesSerializer then rethrows as proto corruption, so even a transient IO
 * failure can crash the app (example: EBADF).
 *
 * Here we uniformly switch to "discard the file and rebuild from defaults": a corrupted or
 * unreadable settings file only loses that group of settings — no more crashes, no repeated
 * triggering on every launch; the exception is logged for troubleshooting.
 */
val preferencesCorruptionHandler: ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { error ->
        FileLogger.e(TAG, "Preferences file unavailable, reset to defaults", error)
        emptyPreferences()
    }
