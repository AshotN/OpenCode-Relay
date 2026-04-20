package com.ashotn.opencode.relay.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.util.Base64

@Service(Service.Level.PROJECT)
class OpenCodeServerAuth(private val project: Project) {

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

    fun password(): String = PasswordSafe.instance.getPassword(attributes()).orEmpty()

    fun setPassword(password: String) {
        val credentials = password.takeIf { it.isNotEmpty() }?.let {
            Credentials(credentialUserName(), it)
        }
        PasswordSafe.instance.set(attributes(), credentials)
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
            ?: error("Server launch authentication is enabled but credentials are incomplete")
    }

    private fun attributes(): CredentialAttributes =
        CredentialAttributes(
            generateServiceName("OpenCode Relay", "Server Authentication"),
            credentialUserName(),
        )

    private fun credentialUserName(): String = "server-auth:${project.locationHash}"

    companion object {
        fun getInstance(project: Project): OpenCodeServerAuth =
            project.getService(OpenCodeServerAuth::class.java)
    }
}
