package com.ashotn.opencode.relay.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

@Service(Service.Level.PROJECT)
class OpenCodeServerAuth(private val project: Project) : Disposable {

    @Volatile
    private var cachedPassword: String = ""

    private val passwordLoaded = CountDownLatch(1)
    private val passwordRevision = AtomicLong(0)
    private val passwordStoreExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opencode-auth-store").apply { isDaemon = true }
    }

    init {
        loadPasswordInBackground()
    }

    class MissingServerLaunchAuthCredentialsException : IllegalStateException(
        "Server launch authentication is enabled but credentials are incomplete",
    )

    data class BasicAuthCredentials(
        val username: String,
        val password: String,
    ) {
        fun authorizationHeader(): String {
            val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
            return "Basic $token"
        }

        fun environmentVariables(): Map<String, String> = mapOf(
            "OPENCODE_SERVER_USERNAME" to username,
            "OPENCODE_SERVER_PASSWORD" to password,
        )
    }

    fun password(): String {
        if (!isPasswordLoaded() && !SwingUtilities.isEventDispatchThread()) {
            awaitPasswordLoaded()
        }
        return cachedPassword
    }

    fun isPasswordLoaded(): Boolean = passwordLoaded.count == 0L

    fun awaitPasswordLoaded() {
        if (isPasswordLoaded()) return
        if (SwingUtilities.isEventDispatchThread()) {
            logger.warn("Refusing to wait for OpenCode server authentication password on EDT")
            return
        }

        try {
            passwordLoaded.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Interrupted while waiting for OpenCode server authentication password", e)
        }
    }

    fun setPassword(password: String) {
        passwordRevision.incrementAndGet()
        cachedPassword = password
        passwordLoaded.countDown()
        try {
            passwordStoreExecutor.execute {
                storePassword(password)
            }
        } catch (e: RejectedExecutionException) {
            logger.warn("Failed to schedule OpenCode server authentication password storage", e)
        }
    }

    fun setPasswordAndWait(password: String): Boolean {
        val revision = passwordRevision.incrementAndGet()
        passwordLoaded.countDown()
        val stored = try {
            passwordStoreExecutor.submit<Boolean> {
                storePassword(password)
            }.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Interrupted while storing OpenCode server authentication password", e)
            false
        } catch (e: Exception) {
            logger.warn("Failed to store OpenCode server authentication password", e)
            false
        }
        if (stored && passwordRevision.get() == revision) {
            cachedPassword = password
        }
        return stored
    }

    override fun dispose() {
        passwordStoreExecutor.shutdown()
    }

    private fun storePassword(password: String): Boolean =
        try {
            val credentials = password.takeIf { it.isNotEmpty() }?.let {
                Credentials(credentialUserName(), it)
            }
            PasswordSafe.instance.set(attributes(), credentials)
            true
        } catch (e: Exception) {
            logger.warn("Failed to store OpenCode server authentication password", e)
            false
        }

    fun connectionCredentials(): BasicAuthCredentials? {
        val password = password()
        if (password.isEmpty()) return null

        val username = OpenCodeSettings.getInstance(project).serverAuthUsername
            .trim()
            .ifEmpty { OpenCodeSettings.DEFAULT_SERVER_AUTH_USERNAME }
        return BasicAuthCredentials(username = username, password = password)
    }

    fun connectionAuthorizationHeader(): String? = connectionCredentials()?.authorizationHeader()

    fun connectionEnvironmentVariables(): Map<String, String> =
        connectionCredentials()?.environmentVariables().orEmpty()

    fun serverLaunchEnvironmentVariables(): Map<String, String> {
        val settings = OpenCodeSettings.getInstance(project)
        if (!settings.protectPluginLaunchedServerWithAuth) return emptyMap()
        return connectionCredentials()?.environmentVariables()
            ?: throw MissingServerLaunchAuthCredentialsException()
    }

    private fun attributes(): CredentialAttributes =
        CredentialAttributes(
            generateServiceName("OpenCode Relay", "Server Authentication"),
            credentialUserName(),
        )

    private fun credentialUserName(): String = "server-auth:${project.locationHash}"

    private fun loadPasswordInBackground() {
        val revision = passwordRevision.get()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val loadedPassword = PasswordSafe.instance.getPassword(attributes()).orEmpty()
                if (passwordRevision.get() == revision) {
                    cachedPassword = loadedPassword
                }
            } catch (e: Exception) {
                logger.warn("Failed to load OpenCode server authentication password", e)
            } finally {
                passwordLoaded.countDown()
            }
        }
    }

    companion object {
        private val logger = logger<OpenCodeServerAuth>()

        fun getInstance(project: Project): OpenCodeServerAuth =
            project.getService(OpenCodeServerAuth::class.java)
    }
}
