package com.vowi.vowidroid.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.vowi.vowidroid.R

/** Permission checks that also work on ROMs with their own permission layer. */
object Permissions {
    fun granted(context: Context, permission: String): Boolean =
        PermissionChecker.checkSelfPermission(context, permission) == PermissionChecker.PERMISSION_GRANTED

    /**
     * Some ROMs (e.g. HyperOS) report a permission as granted while their own
     * layer still blocks it; no dialog can fix that, only the app's settings page.
     */
    fun blockedByRom(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !granted(context, permission)

    fun openAppSettings(context: Context) {
        Toast.makeText(context, R.string.permission_open_settings, Toast.LENGTH_LONG).show()
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
