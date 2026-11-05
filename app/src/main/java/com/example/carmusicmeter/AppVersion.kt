package com.example.carmusicmeter

import android.content.Context
import com.example.carmusicmeter.data.parseBuildDate

object AppVersion {

    fun name(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "?" }

    /**
     * Время сборки APK в мс. Раньше него настенные часы идти не могут,
     * поэтому оно используется как нижняя граница достоверности времени.
     */
    fun buildTimeMs(): Long? = parseBuildDate(BuildConfig.BUILD_DATE)
}
