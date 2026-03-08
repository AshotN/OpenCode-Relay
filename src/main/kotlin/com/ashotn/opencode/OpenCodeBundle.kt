package com.ashotn.opencode

import com.intellij.DynamicBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
@NonNls
private const val BUNDLE = "messages.OpenCodeBundle"

internal object OpenCodeBundle {
    private val INSTANCE = DynamicBundle(OpenCodeBundle::class.java, BUNDLE)

    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): String = INSTANCE.getMessage(key, *params)


}
