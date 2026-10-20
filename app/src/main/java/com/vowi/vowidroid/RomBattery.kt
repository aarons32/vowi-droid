package com.vowi.vowidroid

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * The battery layer some ROMs put on top of Android's own: One UI's sleeping
 * apps, HyperOS/MIUI's autostart switch, EMUI's app launch rules, ColorOS's
 * and OriginOS's background permissions. Android's allow-list does not cover
 * them, and only MIUI's can be read back, so the setup page sends the user to
 * the ROM's own page with the words to look for.
 */
object RomBattery {
    enum class Rom(val label: String) { ONE_UI("One UI"), HYPEROS("HyperOS"), EMUI("EMUI"), COLOROS("ColorOS"), ORIGIN_OS("OriginOS") }

    /** One page of the ROM's settings app: an action, or an exact activity. */
    data class Page(val action: String? = null, val pkg: String? = null, val cls: String? = null)

    /** MIUI's private app-op behind the Autostart switch; readable through the hidden int form of checkOpNoThrow. */
    private const val MIUI_OP_AUTO_START = 10008

    val current: Rom? get() = romFor(Build.MANUFACTURER, Build.BRAND)

    fun romFor(manufacturer: String, brand: String): Rom? {
        val maker = manufacturer.trim().lowercase()
        val mark = brand.trim().lowercase()
        return when {
            maker == "samsung" -> Rom.ONE_UI
            maker == "xiaomi" || mark == "xiaomi" || mark == "redmi" || mark == "poco" -> Rom.HYPEROS
            maker == "huawei" || maker == "honor" -> Rom.EMUI
            maker == "oppo" || maker == "realme" || maker == "oneplus" -> Rom.COLOROS
            maker == "vivo" || maker == "iqoo" -> Rom.ORIGIN_OS
            else -> null
        }
    }

    /** Whether the ROM lets VowiDroid run in the background, when it will say: only HyperOS/MIUI does. Null means unknown. */
    fun allowed(context: Context): Boolean? = when (current) {
        Rom.HYPEROS -> miuiAutostart(context)
        else -> null
    }

    private fun miuiAutostart(context: Context): Boolean? = try {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val check = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
        when (check.invoke(ops, MIUI_OP_AUTO_START, Process.myUid(), context.packageName) as Int) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> false
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /** The ROM's pages for its background rules, most specific first; the phone may have only some of them. */
    fun pages(rom: Rom): List<Page> = when (rom) {
        Rom.ONE_UI -> listOf(
            Page(action = "com.samsung.android.sm.ACTION_BATTERY"),
            Page(pkg = "com.samsung.android.lool", cls = "com.samsung.android.sm.battery.ui.BatteryActivity"),
            Page(pkg = "com.samsung.android.lool", cls = "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        Rom.HYPEROS -> listOf(
            Page(action = "miui.intent.action.OP_AUTO_START"),
            Page(pkg = "com.miui.securitycenter", cls = "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        Rom.EMUI -> listOf(
            Page(pkg = "com.huawei.systemmanager", cls = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Page(pkg = "com.huawei.systemmanager", cls = "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            Page(pkg = "com.huawei.systemmanager", cls = "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        Rom.COLOROS -> listOf(
            Page(pkg = "com.coloros.safecenter", cls = "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            Page(pkg = "com.coloros.safecenter", cls = "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            Page(pkg = "com.oplus.battery", cls = "com.oplus.powermanager.fuelgaue.PowerConsumptionActivity"),
            Page(pkg = "com.oneplus.security", cls = "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        )
        Rom.ORIGIN_OS -> listOf(
            Page(pkg = "com.vivo.permissionmanager", cls = "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            Page(pkg = "com.iqoo.secure", cls = "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        )
    }

    /**
     * Opens the first of the ROM's pages the phone has, else the app's own
     * details page, where One UI and ColorOS also keep their battery choice.
     * False when nothing could be opened.
     */
    fun open(context: Context): Boolean {
        val rom = current ?: return false
        for (page in pages(rom)) {
            val intent = page.intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (context.packageManager.resolveActivity(intent, 0) == null) continue
            // Some vendors keep the activity private; the next page or the app details still work.
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(details) }.isSuccess
    }

    private fun Page.intent(): Intent {
        val intent = if (action != null) Intent(action).addCategory(Intent.CATEGORY_DEFAULT) else Intent()
        if (pkg != null && cls != null) intent.component = ComponentName(pkg, cls)
        return intent
    }
}
