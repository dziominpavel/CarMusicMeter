package com.example.carmusicmeter.probe

import android.app.Notification
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView

/**
 * Достаёт текст из пользовательского layout уведомления.
 *
 * Некоторые штатные плееры не кладут title/text в extras, а рисуют их
 * в `RemoteViews`. Применяем layout в контексте пакета-источника и читаем
 * только текст; никакие действия уведомления не выполняются.
 */
object NotificationTextExtractor {

    @Suppress("DEPRECATION")
    fun extras(notification: Notification): Map<String, String> {
        val bundle = notification.extras ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        bundle.keySet().forEach { key ->
            val value = runCatching { bundle.get(key) }.getOrNull() ?: return@forEach
            when (value) {
                is String -> result[key] = value.take(300)
                is CharSequence -> result[key] = value.toString().take(300)
                is Number, is Boolean -> result[key] = value.toString()
                else -> result[key] = value.javaClass.simpleName
            }
        }
        return result
    }

    @Suppress("DEPRECATION")
    fun remoteTexts(
        context: Context,
        packageName: String,
        notification: Notification,
    ): List<String> {
        val packageContext = runCatching {
            context.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return emptyList()
        val views = listOfNotNull(
            notification.contentView,
            notification.bigContentView,
            notification.headsUpContentView,
        )
        val texts = LinkedHashSet<String>()
        views.forEach { remote ->
            texts += applyTexts(packageContext, remote)
        }
        return texts.map { it.take(300) }.take(20)
    }

    private fun applyTexts(context: Context, remote: RemoteViews): List<String> = runCatching {
        val parent = FrameLayout(context)
        val view = remote.apply(context, parent)
        val out = ArrayList<String>(8)
        collect(view, out)
        out.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }.getOrDefault(emptyList())

    private fun collect(view: View, out: MutableList<String>) {
        if (view.visibility != View.VISIBLE) return
        if (view is TextView) {
            view.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        }
        view.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collect(view.getChildAt(index), out)
            }
        }
    }
}
