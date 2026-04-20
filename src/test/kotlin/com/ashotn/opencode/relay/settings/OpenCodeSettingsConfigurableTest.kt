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
import javax.swing.text.JTextComponent
import kotlin.test.assertFailsWith

class OpenCodeSettingsConfigurableTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            val settings = OpenCodeSettings.getInstance(project)
            settings.serverAuthUsername = OpenCodeSettings.DEFAULT_SERVER_AUTH_USERNAME
            settings.protectPluginLaunchedServerWithAuth = false
            OpenCodeServerAuth.getInstance(project).setPassword("")
        } finally {
            super.tearDown()
        }
    }

    fun testIsModifiedWhenOnlyCorsOriginsChange() {
        val configurable = OpenCodeSettingsConfigurable(project)

        try {
            getOnEdt { configurable.createComponent() }

            runOnEdt {
                configurable.serverCorsOriginsModel.setItems(
                    listOf(OpenCodeSettingsConfigurable.CorsOriginRow("http://localhost:5173"))
                )
            }

            assertTrue(configurable.isModified())
        } finally {
            runOnEdt { configurable.disposeUIResources() }
        }
    }

    fun testIsModifiedWhenOnlyEnvironmentVariablesChange() {
        val configurable = OpenCodeSettingsConfigurable(project)

        try {
            getOnEdt { configurable.createComponent() }

            runOnEdt {
                configurable.serverEnvironmentVariablesModel.setItems(
                    listOf(OpenCodeSettings.EnvironmentVariable("FOO", "bar"))
                )
            }

            assertTrue(configurable.isModified())
        } finally {
            runOnEdt { configurable.disposeUIResources() }
        }
    }

    fun testApplyPersistsServerEnvironmentVariables() {
        val settings = OpenCodeSettings.getInstance(project)
        val configurable = OpenCodeSettingsConfigurable(project)

        try {
            getOnEdt { configurable.createComponent() }

            runOnEdt {
                configurable.serverEnvironmentVariablesModel.setItems(
                    listOf(
                        OpenCodeSettings.EnvironmentVariable("FOO", "bar"),
                        OpenCodeSettings.EnvironmentVariable("HELLO", "world"),
                    )
                )
                configurable.apply()
            }

            assertEquals(
                listOf(
                    OpenCodeSettings.EnvironmentVariable("FOO", "bar"),
                    OpenCodeSettings.EnvironmentVariable("HELLO", "world"),
                ),
                settings.serverEnvironmentVariables,
            )
        } finally {
            runOnEdt { configurable.disposeUIResources() }
        }
    }

    fun testApplyPersistsServeCommandOptions() {
        val settings = OpenCodeSettings.getInstance(project)
        val configurable = OpenCodeSettingsConfigurable(project)

        try {
            getOnEdt { configurable.createComponent() }

            runOnEdt {
                configurable.serverHostnameField.text = "0.0.0.0"
                configurable.serverMdnsEnabledCheckBox.isSelected = true
                configurable.serverMdnsDomainField.text = "relay.local"
                configurable.serverCorsOriginsModel.setItems(
                    listOf(
                        OpenCodeSettingsConfigurable.CorsOriginRow("http://localhost:5173"),
                        OpenCodeSettingsConfigurable.CorsOriginRow("https://app.example.com"),
                    )
                )
                configurable.apply()
            }

            assertEquals("0.0.0.0", settings.serverHostname)
            assertTrue(settings.serverMdnsEnabled)
            assertEquals("relay.local", settings.serverMdnsDomain)
            assertEquals("http://localhost:5173\nhttps://app.example.com", settings.serverCorsOrigins)
        } finally {
            runOnEdt { configurable.disposeUIResources() }
        }
    }

    fun testApplyPersistsServerAuthenticationSettings() {
        val settings = OpenCodeSettings.getInstance(project)
        val serverAuth = OpenCodeServerAuth.getInstance(project)
        val configurable = OpenCodeSettingsConfigurable(project)

        try {
            getOnEdt { configurable.createComponent() }

            runOnEdt {
                configurable.serverAuthUsernameField.text = "alice"
                configurable.serverAuthPasswordField.text = "secret"
                configurable.protectPluginLaunchedServerWithAuthCheckBox.isSelected = true
                configurable.apply()
            }

            assertEquals("alice", settings.serverAuthUsername)
            assertTrue(settings.protectPluginLaunchedServerWithAuth)
            assertEquals("secret", serverAuth.password())
        } finally {
            runOnEdt { configurable.disposeUIResources() }
        }
    }

    fun testApplyDefaultsBlankServerAuthUsername() {
        val settings = OpenCodeSettings.getInstance(project)
        val configurable = OpenCodeSettingsConfigurable(project)

        try {
            getOnEdt { configurable.createComponent() }

            runOnEdt {
                configurable.serverAuthUsernameField.text = "   "
                configurable.apply()
            }

            assertEquals(OpenCodeSettings.DEFAULT_SERVER_AUTH_USERNAME, settings.serverAuthUsername)
        } finally {
            runOnEdt { configurable.disposeUIResources() }
        }
    }

    fun testApplyRejectsProtectedServerWithoutPassword() {
        val configurable = OpenCodeSettingsConfigurable(project)

        try {
            getOnEdt { configurable.createComponent() }

            runOnEdt {
                configurable.protectPluginLaunchedServerWithAuthCheckBox.isSelected = true
                configurable.serverAuthPasswordField.text = ""
            }

            val exception = assertFailsWith<ConfigurationException> {
                runOnEdt { configurable.apply() }
            }
            assertTrue(exception.localizedMessage.orEmpty().contains("protect the server launched by plugin"))
        } finally {
            runOnEdt { configurable.disposeUIResources() }
        }
    }

    fun testApplyCommitsActiveCorsCellEdit() {
        val settings = OpenCodeSettings.getInstance(project)
        val configurable = OpenCodeSettingsConfigurable(project)

        try {
            getOnEdt { configurable.createComponent() }

            runOnEdt {
                configurable.serverCorsOriginsModel.setItems(
                    listOf(OpenCodeSettingsConfigurable.CorsOriginRow("http://localhost:5173"))
                )
                assertTrue(configurable.serverCorsOriginsTable.editCellAt(0, 0))
                val editor = configurable.serverCorsOriginsTable.editorComponent as? JTextComponent
                    ?: error("CORS editor component not found")
                editor.text = "https://app.example.com"
                configurable.apply()
            }

            assertEquals("https://app.example.com", settings.serverCorsOrigins)
        } finally {
            runOnEdt { configurable.disposeUIResources() }
        }
    }

    fun testApplyPreservesEnvValueWhitespaceAndDropsBlankNames() {
        val settings = OpenCodeSettings.getInstance(project)
        val configurable = OpenCodeSettingsConfigurable(project)

        try {
            getOnEdt { configurable.createComponent() }

            runOnEdt {
                configurable.serverEnvironmentVariablesModel.setItems(
                    listOf(
                        OpenCodeSettings.EnvironmentVariable(" FOO ", "  spaced value  "),
                        OpenCodeSettings.EnvironmentVariable("", "should be dropped"),
                    )
                )
                configurable.apply()
            }

            assertEquals(
                listOf(OpenCodeSettings.EnvironmentVariable("FOO", "  spaced value  ")),
                settings.serverEnvironmentVariables,
            )
        } finally {
            runOnEdt { configurable.disposeUIResources() }
        }
    }

    fun testApplyRejectsReservedServerAuthEnvironmentVariables() {
        val configurable = OpenCodeSettingsConfigurable(project)

        try {
            getOnEdt { configurable.createComponent() }

            runOnEdt {
                configurable.serverEnvironmentVariablesModel.setItems(
                    listOf(OpenCodeSettings.EnvironmentVariable("OPENCODE_SERVER_PASSWORD", "secret"))
                )
            }

            val exception = assertFailsWith<ConfigurationException> {
                runOnEdt { configurable.apply() }
            }
            assertTrue(exception.localizedMessage.orEmpty().contains("Server Authentication fields"))
        } finally {
            runOnEdt { configurable.disposeUIResources() }
        }
    }

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
