package com.example.carmusicmeter.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Доступ к необязательному чтению интерфейса штатного плеера. */
object AccessibilityAccess {

    fun component(context: Context): ComponentName =
        ComponentName(context, StockPlayerAccessibilityService::class.java)

    fun isEnabled(context: Context): Boolean {
        val enabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull() ?: return false
        val packageName = context.packageName
        val className = StockPlayerAccessibilityService::class.java.name
        val shortName = className.substringAfterLast('.')
        return enabled.split(':').any { item ->
            item.contains(packageName) &&
                (item.contains(className) || item.endsWith(shortName))
        }
    }

    fun openSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
}
