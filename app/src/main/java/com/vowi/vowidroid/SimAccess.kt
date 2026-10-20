package com.vowi.vowidroid

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

/**
 * What VowiDroid needs before it can use the phone's own SIM:
 * - the phone permission, to list SIM subscriptions;
 * - Android 12+'s USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER app-op, which allows
 *   reading the IMSI/ICCID and running USIM authentication. It can only be
 *   granted from adb or root, once; Android keeps it across reboots.
 */
object SimAccess {
    const val ISSUE_PERMISSION = "android_sim_permission"
    const val ISSUE_ACCESS = "android_sim_access"
    const val ISSUE_UNSUPPORTED = "android_sim_unsupported"

    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun hasPhonePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    fun hasSimAuthentication(context: Context): Boolean {
        if (!supported()) return false
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val operation = AppOpsManager.permissionToOp(Manifest.permission.USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER)
            ?: return false
        return try {
            appOps.unsafeCheckOpNoThrow(operation, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /** Why phone SIMs cannot be used yet, or null when they can. */
    fun issue(context: Context): String? = when {
        !supported() -> ISSUE_UNSUPPORTED
        !hasPhonePermission(context) -> ISSUE_PERMISSION
        !hasSimAuthentication(context) -> ISSUE_ACCESS
        else -> null
    }

    fun grantCommand(context: Context): String =
        "adb shell appops set ${context.packageName} USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER allow"

    /** Prints "allow" once the grant is in place. */
    fun checkCommand(context: Context): String =
        "adb shell appops get ${context.packageName} USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER"

    /**
     * Grants the app-op through `su`. Blocks for up to 20 seconds while the
     * root manager asks the user, so call it off the main thread.
     */
    fun grantWithRoot(context: Context): Boolean {
        val command = "appops set ${context.packageName} USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER allow"
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroy()
                false
            } else {
                process.exitValue() == 0 && hasSimAuthentication(context)
            }
        } catch (_: Exception) {
            false
        }
    }
}
