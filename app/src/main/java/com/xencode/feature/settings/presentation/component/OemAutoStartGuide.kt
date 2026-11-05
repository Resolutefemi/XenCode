package com.xencode.feature.settings.presentation.component

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.xencode.core.util.FileLogger

/**
 * Vendor autostart management jump: Chinese ROMs (MIUI/EMUI/ColorOS/OriginOS/Flyme) forbid app autostart by default,
 * and both background processes and foreground services get reclaimed by the system. Tapping "Autostart management" in settings jumps to the vendor's autostart settings page;
 * when the intent is unavailable on that ROM, fall back to this app's details page (the user can enable background running/autostart manually).
 */
object OemAutoStartGuide {
    private const val TAG = "OemAutoStartGuide"

    private data class VendorEntry(val component: String, val keywords: List<String>)

    // Each vendor's autostart management page (package/Activity). Existence verified with resolveActivity before jumping.
    private val vendorEntries = listOf(
        VendorEntry(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            listOf("xiaomi", "redmi")
        ),
        VendorEntry(
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            listOf("huawei", "honor")
        ),
        VendorEntry(
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            listOf("oppo", "realme", "oneplus")
        ),
        VendorEntry(
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            listOf("vivo", "iqoo")
        ),
        VendorEntry(
            "com.meizu.safe/com.meizu.safe.permission.SmartBGActivity",
            listOf("meizu")
        )
    )

    /** Try to jump to the vendor's autostart management page; fall back to the app details page when there is no match or the jump fails. */
    fun openAutoStartSettings(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val entry = vendorEntries.firstOrNull { vendor ->
            vendor.keywords.any { manufacturer.contains(it) }
        }
        if (entry != null && startResolved(context, entry.component)) return
        openAppDetails(context)
    }

    private fun startResolved(context: Context, component: String): Boolean {
        val (pkg, cls) = component.split("/", limit = 2)
        val intent = Intent().setComponent(ComponentName(pkg, cls))
        if (intent.resolveActivity(context.packageManager) == null) {
            FileLogger.w(TAG, "Autostart settings page unavailable: $component")
            return false
        }
        return runCatching { context.startActivity(intent) }
            .onFailure { FileLogger.e(TAG, "Failed to jump to autostart settings: $component", it) }
            .isSuccess
    }

    /** Jump to this app's system settings details page (permissions page). Reused by permission reminder dialogs. */
    internal fun openAppDetails(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { context.startActivity(intent) }
            .onFailure { FileLogger.e(TAG, "Failed to jump to app details page", it) }
    }
}