package com.ashotn.opencode.util

import com.intellij.openapi.application.ApplicationInfo

/**
 * Utilities for checking the host IDE's build number at runtime, used to gate
 * features that require a specific platform version.
 */
object BuildUtils {

    /**
     * Returns true if the running IDE's baseline version is >= [minBuild].
     *
     * Uses [BuildNumber.baselineVersion] directly, e.g. `253` for 2025.3.
     */
    fun isAtLeastBuild(minBuild: Int): Boolean {
        return ApplicationInfo.getInstance().build.baselineVersion >= minBuild
    }

    /**
     * True when the embedded reworked terminal APIs (TerminalToolWindowTabsManager, etc.)
     * are available. These were introduced in IntelliJ 2025.3 (build 253).
     */
    val isEmbeddedTerminalSupported: Boolean
        get() = isAtLeastBuild(253)
}
