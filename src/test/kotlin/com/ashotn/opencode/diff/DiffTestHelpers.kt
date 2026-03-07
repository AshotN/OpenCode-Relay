package com.ashotn.opencode.diff

import com.intellij.openapi.diagnostic.Logger

internal object NoOpLogger : Logger() {
    override fun isDebugEnabled() = false
    override fun debug(message: String, t: Throwable?) = Unit
    override fun debug(t: Throwable?) = Unit
    override fun debug(message: String, vararg details: Any?) = Unit
    override fun info(message: String) = Unit
    override fun info(message: String, t: Throwable?) = Unit
    override fun warn(message: String, t: Throwable?) = Unit
    override fun error(message: String, t: Throwable?, vararg details: String) = Unit
}
