package io.github.mbaliga.fylz.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

/**
 * The runtime permission flow for the `full` flavor's broad-access backend.
 *
 * `MANAGE_EXTERNAL_STORAGE` is *not* a dangerous runtime permission and cannot be requested with
 * `ActivityResultContracts.RequestPermission`. The only way to obtain it is to send the user to
 * the system's "All files access" screen and re-check `Environment.isExternalStorageManager()`
 * when they come back -- which is what [intent] and [isGranted] are for.
 *
 * This whole file exists only in `app/src/full`, so the `saf` flavor cannot even reference it.
 */
object FullAccessPermission {

    /** True once the user has granted "All files access" to Fylz. */
    fun isGranted(): Boolean = Environment.isExternalStorageManager()

    /**
     * Intent for the system "All files access" screen.
     *
     * Prefers the per-app variant so the user lands on Fylz's own toggle; falls back to the
     * device-wide list, which every Android build that supports the permission exposes.
     */
    fun intent(context: Context): Intent {
        val perApp = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )
        val resolvable = context.packageManager
            .queryIntentActivities(perApp, 0)
            .isNotEmpty()
        return if (resolvable) perApp else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }

    /** One sentence explaining what the grant buys, shown above the button that fires [intent]. */
    const val RATIONALE: String =
        "Fylz needs All files access to list your storage volumes and standard folders without " +
            "asking you to pick each one. Nothing leaves the device."
}
