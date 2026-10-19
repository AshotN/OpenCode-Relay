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
import java.security.SecureRandom
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

    data class BasicAuthCredentials(
        val username: String,
        val password: String,
    ) {
        fun authorizationHeader(): String {
            val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
            return "Basic $token"
        }

        /**
         * Environment variables that authenticate a process against an OpenCode 2.x server.
         * v2 reads the server password from `OPENCODE_PASSWORD` (the v1
         * `OPENCODE_SERVER_PASSWORD` variable is no longer recognized).
         */
        fun environmentVariables(): Map<String, String> = mapOf(
            "OPENCODE_PASSWORD" to password,
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

    /**
     * Updates the in-memory password cache without persisting to the IDE credential
     * store. Used for passwords sourced from the OpenCode background service state
     * file, which is re-read on every IDE startup.
     *
     * For the rest of the session the transient password shadows any password
     * stored in the IDE password safe: all connections (API, SSE, launched
     * processes via [ensureLaunchPassword]) use it, while the settings UI keeps
     * showing the stored password. The shadow ends when the project service is
     * disposed, at which point the stored password (or a generated one) applies
     * again.
     */
    fun setPasswordTransiently(password: String) {
        passwordRevision.incrementAndGet()
        cachedPassword = password
        passwordLoaded.countDown()
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

        // OpenCode 2.x always authenticates as the fixed "opencode" user.
        return BasicAuthCredentials(
            username = OpenCodeSettings.DEFAULT_SERVER_AUTH_USERNAME,
            password = password,
        )
    }

    fun connectionAuthorizationHeader(): String? = connectionCredentials()?.authorizationHeader()

    fun connectionEnvironmentVariables(): Map<String, String> =
        connectionCredentials()?.environmentVariables().orEmpty()

    /**
     * Environment variables for a server process launched by the plugin.
     *
     * OpenCode 2.x servers always require authentication, so a launch password is
     * guaranteed: the stored password is reused, or a fresh one is generated.
     */
    fun launchEnvironmentVariables(): Map<String, String> =
        mapOf("OPENCODE_PASSWORD" to ensureLaunchPassword())

    /**
     * Returns the password the next plugin-launched server should use,
     * generating and storing one when none is available.
     */
    fun ensureLaunchPassword(): String {
        val stored = password()
        if (stored.isNotEmpty()) return stored
        val generated = generatePassword()
        setPassword(generated)
        return generated
    }

    private fun generatePassword(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
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
