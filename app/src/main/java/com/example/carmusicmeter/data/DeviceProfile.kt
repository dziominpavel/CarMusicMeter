package com.example.carmusicmeter.data

/**
 * Определение штатной магнитолы Teyes по данным Build. Вынесено из службы,
 * чтобы экран и служба не расходились в том, показывать ли Teyes-источники.
 */
object DeviceProfile {

    fun isTeyes(manufacturer: String?, model: String?): Boolean =
        manufacturer.equals("sprd", ignoreCase = true) ||
            model?.contains("ums512", ignoreCase = true) == true
}
