package com.vowi.vowidroid

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.vowi.vowidroid.ui.Permissions

/**
 * Everything VowiDroid asks Android for, in the order the setup page lists it,
 * and whether each is in place. Only [Need.CONTACTS] is optional.
 */
object AppPermissions {
    enum class Need(
        /** The runtime permission behind it, when a system dialog can grant it. */
        val permission: String? = null,
        val optional: Boolean = false,
    ) {
        PHONE(Manifest.permission.READ_PHONE_STATE),
        SIM_ACCESS,
        NOTIFICATIONS(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null),
        MICROPHONE(Manifest.permission.RECORD_AUDIO),
        FULL_SCREEN,
        /** Android's own battery allow-list: the tunnel and the registration stay up with the screen off. */
        BATTERY,
        /** The ROM's extra layer (One UI sleeping apps, HyperOS autostart, …); only on phones that have one. */
        ROM_BACKGROUND,
        CONTACTS(Manifest.permission.READ_CONTACTS, optional = true),
    }

    /** True or false when the phone will say, null for a setting VowiDroid cannot read back. */
    fun status(context: Context, need: Need): Boolean? = when (need) {
        Need.PHONE -> Permissions.granted(context, Manifest.permission.READ_PHONE_STATE)
        Need.SIM_ACCESS -> SimAccess.hasSimAuthentication(context)
        Need.NOTIFICATIONS -> NotificationManagerCompat.from(context).areNotificationsEnabled()
        Need.MICROPHONE -> Permissions.granted(context, Manifest.permission.RECORD_AUDIO)
        Need.FULL_SCREEN -> Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        Need.BATTERY -> context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        Need.ROM_BACKGROUND -> RomBattery.allowed(context)
        Need.CONTACTS -> Permissions.granted(context, Manifest.permission.READ_CONTACTS)
    }

    fun granted(context: Context, need: Need): Boolean = status(context, need) == true

    /** The need's name as the Permissions page lists it. */
    fun title(context: Context, need: Need): String = when (need) {
        Need.PHONE -> context.getString(R.string.setup_phone_title)
        Need.SIM_ACCESS -> context.getString(R.string.setup_access_title)
        Need.NOTIFICATIONS -> context.getString(R.string.setup_notifications_title)
        Need.MICROPHONE -> context.getString(R.string.setup_microphone_title)
        Need.FULL_SCREEN -> context.getString(R.string.setup_full_screen_title)
        Need.BATTERY -> context.getString(R.string.setup_battery_title)
        Need.ROM_BACKGROUND -> context.getString(R.string.setup_rom_title, RomBattery.current?.label.orEmpty())
        Need.CONTACTS -> context.getString(R.string.setup_contacts_title)
    }

    /** SIM access needs Android 12; the ROM item exists only on ROMs with their own layer. */
    fun supported(need: Need): Boolean = when (need) {
        Need.SIM_ACCESS -> SimAccess.supported()
        Need.ROM_BACKGROUND -> RomBattery.current != null
        else -> true
    }

    /** The required needs known to be missing, i.e. what the setup page still has to fix. */
    fun missing(context: Context): List<Need> =
        Need.entries.filter { !it.optional && supported(it) && status(context, it) == false }

    /**
     * The settings page that grants a need no dialog can: the app's notification
     * page, the full-screen-intent page, the battery allow-list request, or the
     * app's details for a permission a ROM blocks after its dialog said yes. Null
     * for SIM access (adb or root only) and the ROM layer ([RomBattery.open]).
     */
    @SuppressLint("BatteryLife") // a phone app: the tunnel and the IMS registration must outlive the screen
    fun settingsIntent(context: Context, need: Need): Intent? {
        val app = Uri.fromParts("package", context.packageName, null)
        return when (need) {
            Need.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            Need.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, app)
            } else {
                null
            }
            Need.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app)
            Need.SIM_ACCESS, Need.ROM_BACKGROUND -> null
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)
        }
    }
}
