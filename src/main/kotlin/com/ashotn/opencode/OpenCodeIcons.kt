package com.ashotn.opencode

import com.intellij.openapi.util.IconLoader

object OpenCodeIcons {
    @JvmField val Connected = IconLoader.getIcon("/icons/opencode.svg", OpenCodeIcons::class.java)
    @JvmField val Disconnected = IconLoader.getIcon("/icons/opencode_disconnected.svg", OpenCodeIcons::class.java)
}
