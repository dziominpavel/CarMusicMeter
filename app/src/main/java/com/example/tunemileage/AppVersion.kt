package com.example.tunemileage

import android.content.Context

object AppVersion {

    fun name(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "?" }
}
