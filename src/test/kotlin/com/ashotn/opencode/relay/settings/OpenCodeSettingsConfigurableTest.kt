package com.ashotn.opencode.relay.settings

import com.ashotn.opencode.relay.OpenCodeExecutableResolutionState
import com.ashotn.opencode.relay.OpenCodeInfo
import com.ashotn.opencode.relay.OpenCodePlugin
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFailsWith

class OpenCodeSettingsConfigurableTest : BasePlatformTestCase() {

    fun testApplyAllowsSavingWhenPathIsBlankAndResolutionFails() {
        val settings = OpenCodeSettings.getInstance(project)
        settings.executablePath = "C:/Users/VM/AppData/Roaming/npm/opencode.cmd"
        OpenCodePlugin.getInstance(project).setExecutableResolutionState(
            OpenCodeExecutableResolutionState.Resolved(OpenCodeInfo(settings.executablePath, "1.2.3"))
        )

        val configurable = OpenCodeSettingsConfigurable(project).apply {
            executableResolver = { null }
        }

        try {
            val component = getOnEdt { configurable.createComponent() }
            val executableField = findFirst(component, TextFieldWithBrowseButton::class.java)
                ?: error("Executable path field not found")

            runOnEdt { executableField.text = "" }
            runOnEdt { configurable.apply() }

            assertEquals("", settings.executablePath)
            assertNull(OpenCodePlugin.getInstance(project).openCodeInfo)
        } finally {
            runOnEdt { configurable.disposeUIResources() }
        }
    }

    fun testApplyBlocksSavingWhenExplicitPathFailsResolution() {
        val settings = OpenCodeSettings.getInstance(project)
        settings.executablePath = "C:/existing/opencode"

        val configurable = OpenCodeSettingsConfigurable(project).apply {
            executableResolver = { null }
        }

        try {
            val component = getOnEdt { configurable.createComponent() }
            val executableField = findFirst(component, TextFieldWithBrowseButton::class.java)
                ?: error("Executable path field not found")

            runOnEdt { executableField.text = "C:/invalid/opencode" }

            assertFailsWith<ConfigurationException> {
                runOnEdt { configurable.apply() }
            }
            assertEquals("C:/existing/opencode", settings.executablePath)
        } finally {
            runOnEdt { configurable.disposeUIResources() }
        }
    }

    fun testApplyAttemptsAutoResolveWhenPathBlankAndResolutionStillPending() {
        val settings = OpenCodeSettings.getInstance(project)
        settings.executablePath = ""
        OpenCodePlugin.getInstance(project).setExecutableResolutionState(OpenCodeExecutableResolutionState.Resolving)

        val resolveCalls = AtomicInteger(0)
        val configurable = OpenCodeSettingsConfigurable(project).apply {
            executableResolver = {
                resolveCalls.incrementAndGet()
                null
            }
        }

        try {
            getOnEdt { configurable.createComponent() }
            runOnEdt { configurable.apply() }

            assertEquals(1, resolveCalls.get())
            assertEquals("", settings.executablePath)
            assertEquals(
                OpenCodeExecutableResolutionState.NotFound,
                OpenCodePlugin.getInstance(project).executableResolutionState,
            )
        } finally {
            runOnEdt { configurable.disposeUIResources() }
        }
    }

    fun testApplySkipsResolveWhenPathBlankAndNotFoundAlreadyResolved() {
        val settings = OpenCodeSettings.getInstance(project)
        settings.executablePath = ""
        OpenCodePlugin.getInstance(project).setExecutableResolutionState(OpenCodeExecutableResolutionState.NotFound)

        val resolveCalls = AtomicInteger(0)
        val configurable = OpenCodeSettingsConfigurable(project).apply {
            executableResolver = {
                resolveCalls.incrementAndGet()
                null
            }
        }

        try {
            getOnEdt { configurable.createComponent() }
            runOnEdt { configurable.apply() }

            assertEquals(0, resolveCalls.get())
            assertEquals("", settings.executablePath)
            assertEquals(
                OpenCodeExecutableResolutionState.NotFound,
                OpenCodePlugin.getInstance(project).executableResolutionState,
            )
        } finally {
            runOnEdt { configurable.disposeUIResources() }
        }
    }

    fun testApplySkipsResolveWhenNoSettingsChangeAndResolutionAlreadyResolved() {
        val settings = OpenCodeSettings.getInstance(project)
        settings.executablePath = "C:/existing/opencode"
        OpenCodePlugin.getInstance(project).setExecutableResolutionState(
            OpenCodeExecutableResolutionState.Resolved(OpenCodeInfo(settings.executablePath, "1.2.3"))
        )

        val resolveCalls = AtomicInteger(0)
        val configurable = OpenCodeSettingsConfigurable(project).apply {
            executableResolver = {
                resolveCalls.incrementAndGet()
                OpenCodeInfo("C:/resolved/opencode", "1.2.3")
            }
        }

        try {
            getOnEdt { configurable.createComponent() }
            runOnEdt { configurable.apply() }

            assertEquals(0, resolveCalls.get())
            assertEquals("C:/existing/opencode", settings.executablePath)
        } finally {
            runOnEdt { configurable.disposeUIResources() }
        }
    }

    private fun runOnEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeAndWait(action)
    }

    private fun <T> getOnEdt(action: () -> T): T {
        val resultRef = AtomicReference<T>()
        ApplicationManager.getApplication().invokeAndWait {
            resultRef.set(action())
        }
        return resultRef.get()
    }

    private fun <T : Component> findFirst(root: Component, clazz: Class<T>): T? {
        if (clazz.isInstance(root)) return clazz.cast(root)
        if (root is Container) {
            for (child in root.components) {
                val found = findFirst(child, clazz)
                if (found != null) return found
            }
        }
        return null
    }
}
