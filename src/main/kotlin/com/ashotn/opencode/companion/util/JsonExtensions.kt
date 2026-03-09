package com.ashotn.opencode.companion.util

import com.google.gson.JsonObject

internal fun JsonObject.getStringOrNull(key: String): String? {
    val element = get(key) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
    return element.asString
}

internal fun JsonObject.getIntOrNull(key: String): Int? {
    val element = get(key) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
    return runCatching { element.asInt }.getOrNull()
}

internal fun JsonObject.getObjectOrNull(key: String): JsonObject? {
    val element = get(key) ?: return null
    if (!element.isJsonObject) return null
    return element.asJsonObject
}
