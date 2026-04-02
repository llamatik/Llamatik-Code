package com.llamatik.teammate.util

import com.intellij.openapi.diagnostic.Logger

object PluginLogger {
    fun forClass(clazz: Class<*>): Logger = Logger.getInstance(clazz)
    inline fun <reified T> logger(): Logger = Logger.getInstance(T::class.java)
}
