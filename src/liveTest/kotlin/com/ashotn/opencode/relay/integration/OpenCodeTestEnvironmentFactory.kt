package com.ashotn.opencode.relay.integration

import com.ashotn.opencode.relay.api.health.HealthApiClient
import com.ashotn.opencode.relay.api.session.SessionApiClient
import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.transport.OpenCodeHttpTransport
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText

object OpenCodeTestEnvironmentFactory {

    private const val AUTH_JSON_ENV = "OPENCODE_TEST_AUTH_JSON"
    private const val BIN_ENV = "OPENCODE_TEST_BIN"
    private const val MODEL_ENV = "OPENCODE_TEST_MODEL"
    private const val CONFIG_JSON_ENV = "OPENCODE_TEST_CONFIG_JSON"

    /**
     * Parses `OPENCODE_TEST_MODEL` (`providerID:modelID`) into a session model
     * reference. Live model resolution can flake on small local deployments
     * (e.g. a deprecated catalog model is selected), so pinning the model makes
     * the live tests deterministic.
     */
    fun testModel(): SessionApiClient.SessionModelRef? =
        System.getenv(MODEL_ENV)
            ?.takeIf { it.isNotBlank() }
            ?.split(":", limit = 2)
            ?.takeIf { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }
            ?.let { SessionApiClient.SessionModelRef(providerID = it[0], modelID = it[1]) }

    fun create(version: String, allowTask: Boolean = false): OpenCodeTestEnvironment {
        val projectRoot = Path.of("").toAbsolutePath().normalize()
        val opencodeBin = resolveOpenCodeBinary(projectRoot, version)

        val installedVersion = readVersion(opencodeBin)
        require(installedVersion == version) {
            "Expected OpenCode version $version at $opencodeBin but found $installedVersion"
        }

        val runsRoot = projectRoot.resolve(".cache/opencode-test/runs/$version")
        runsRoot.createDirectories()

        val scenarioRoot = Files.createTempDirectory(runsRoot, "scenario-")
        val repoRoot = scenarioRoot.resolve("repo")
        val homeRoot = scenarioRoot.resolve("home")
        val xdgConfigHome = scenarioRoot.resolve("xdg-config")
        val xdgDataHome = scenarioRoot.resolve("xdg-data")
        val xdgCacheHome = scenarioRoot.resolve("xdg-cache")
        val xdgStateHome = scenarioRoot.resolve("xdg-state")
        val artifactsRoot = scenarioRoot.resolve("artifacts")
        val authPath = xdgDataHome.resolve("opencode/auth.json")
        val authJson = loadAuthJson()

        listOf(
            repoRoot,
            homeRoot,
            xdgConfigHome,
            xdgDataHome,
            xdgCacheHome,
            xdgStateHome,
            artifactsRoot,
        ).forEach { it.createDirectories() }

        initializeGitRepo(repoRoot)
        repoRoot.resolve("README.md").writeText("# OpenCode Test Repo\n")
        authPath.parent.createDirectories()
        authPath.writeText(authJson)
        writeConfigJson(xdgConfigHome)

        // OpenCode 2.x servers always require Basic auth. The plugin's launch flow
        // passes a client-generated password via `OPENCODE_PASSWORD` (never parsing
        // server stdout), and the live test environment follows the same design.
        val serverPassword = generateServerPassword()

        val env = linkedMapOf(
            "HOME" to homeRoot.toString(),
            "XDG_CONFIG_HOME" to xdgConfigHome.toString(),
            "XDG_DATA_HOME" to xdgDataHome.toString(),
            "XDG_CACHE_HOME" to xdgCacheHome.toString(),
            "XDG_STATE_HOME" to xdgStateHome.toString(),
            "OPENCODE_TEST_HOME" to homeRoot.toString(),
            "OPENCODE_DISABLE_AUTOUPDATE" to "true",
            "OPENCODE_DISABLE_LSP_DOWNLOAD" to "true",
            "OPENCODE_PASSWORD" to serverPassword,
        )

        return OpenCodeTestEnvironment(
            version = version,
            scenarioRoot = scenarioRoot,
            repoRoot = repoRoot,
            artifactsRoot = artifactsRoot,
            authPath = authPath,
            opencodeBin = opencodeBin,
            serverPassword = serverPassword,
            sessionPermissions = sessionPermissions(allowTask = allowTask),
            testModel = testModel(),
            env = env,
        )
    }

    /**
     * Permission rules for live test sessions, passed at session creation.
     *
     * OpenCode 2.x removed the v1 `OPENCODE_PERMISSION` environment variable and
     * defaults to permissive rules (most actions `allow`; only `doom_loop` and
     * `external_directory` are `ask`). The explicit ruleset below keeps the v1
     * test semantics (no bash, no webfetch) and prevents headless turns from
     * hanging on `ask` requests.
     */
    private fun sessionPermissions(allowTask: Boolean): List<SessionApiClient.PermissionRule> = buildList {
        add(SessionApiClient.PermissionRule(action = "edit", resource = "*", effect = "allow"))
        add(SessionApiClient.PermissionRule(action = "bash", resource = "*", effect = "deny"))
        if (allowTask) {
            add(SessionApiClient.PermissionRule(action = "task", resource = "*", effect = "allow"))
        }
        add(SessionApiClient.PermissionRule(action = "webfetch", resource = "*", effect = "deny"))
        add(SessionApiClient.PermissionRule(action = "doom_loop", resource = "*", effect = "deny"))
        add(SessionApiClient.PermissionRule(action = "external_directory", resource = "*", effect = "deny"))
    }

    private fun generateServerPassword(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun loadAuthJson(): String =
        System.getenv(AUTH_JSON_ENV)
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "Missing required $AUTH_JSON_ENV environment variable for OpenCode test auth.",
            )

    /**
     * Writes `OPENCODE_TEST_CONFIG_JSON` (if set) as the global config
     * (`opencode/opencode.json` under the isolated config home).
     *
     * Live scenarios start with an empty config home, so providers defined only
     * in the user's own `opencode.json` (e.g. a local llama.cpp endpoint with a
     * custom model catalog) are invisible to the test server and model
     * resolution falls back to the public catalog. Passing the provider
     * definition and default model here keeps live tests deterministic on such
     * deployments.
     */
    private fun writeConfigJson(xdgConfigHome: Path) {
        System.getenv(CONFIG_JSON_ENV)
            ?.takeIf { it.isNotBlank() }
            ?.let { config ->
                val configPath = xdgConfigHome.resolve("opencode/opencode.json")
                configPath.parent.createDirectories()
                configPath.writeText(config)
            }
    }

    private fun resolveOpenCodeBinary(projectRoot: Path, version: String): Path {
        // Escape hatch for machines without node/npm (e.g. this dev box, where OpenCode
        // is installed as a standalone native binary): point the suite at a pre-installed
        // binary. The version check below still enforces that it matches the test list.
        System.getenv(BIN_ENV)
            ?.takeIf { it.isNotBlank() }
            ?.let { override ->
                val bin = Path.of(override)
                require(bin.toFile().exists() && bin.toFile().canExecute()) {
                    "$BIN_ENV points at a missing or non-executable file: $bin"
                }
                return bin
            }

        val binRoot = projectRoot.resolve(".cache/opencode-test/bins/$version")
        val opencodeBin = binRoot.resolve("node_modules/.bin/opencode")
        if (!opencodeBin.exists()) {
            installOpenCodeVersion(binRoot, version)
        }
        require(opencodeBin.exists()) {
            "OpenCode $version is not installed at $opencodeBin"
        }
        return opencodeBin
    }

    private fun installOpenCodeVersion(binRoot: Path, version: String) {
        binRoot.createDirectories()
        val process = try {
            ProcessBuilder("npm", "install", "--prefix", binRoot.toString(), "opencode-ai@$version")
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            throw IllegalStateException(
                "npm is not available, so OpenCode $version cannot be installed into $binRoot. " +
                    "Install node/npm, or set $BIN_ENV to a pre-installed opencode binary.",
                e,
            )
        }

        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        require(exitCode == 0) {
            "Failed to install OpenCode $version into $binRoot: $output"
        }
    }

    private fun readVersion(opencodeBin: Path): String {
        val process = ProcessBuilder(opencodeBin.toString(), "--version")
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        require(exitCode == 0) {
            "Failed to read OpenCode version from $opencodeBin: $output"
        }
        return parseVersion(output)
    }

    // `opencode --version` reports e.g. "opencode v2.0.32"; normalize to the bare version.
    private fun parseVersion(output: String): String {
        val withoutPrefix = output.replaceFirst(Regex("^\\S+\\s+v?"), "")
        return withoutPrefix.ifEmpty { output }
    }

    private fun initializeGitRepo(repoRoot: Path) {
        val process = ProcessBuilder("git", "-c", "init.defaultBranch=main", "init", repoRoot.toString())
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        require(exitCode == 0) {
            "Failed to initialize git repo at $repoRoot: $output"
        }
    }
}

class OpenCodeTestEnvironment(
    val version: String,
    val scenarioRoot: Path,
    val repoRoot: Path,
    val artifactsRoot: Path,
    val authPath: Path,
    val opencodeBin: Path,
    val serverPassword: String,
    val sessionPermissions: List<SessionApiClient.PermissionRule>,
    val testModel: SessionApiClient.SessionModelRef?,
    val env: Map<String, String>,
) : AutoCloseable {

    private val healthApiClient = HealthApiClient(
        OpenCodeHttpTransport(
            defaultConnectTimeoutMs = 1_000,
            defaultReadTimeoutMs = 1_000,
            authorizationHeaderProvider = ::authorizationHeader,
        ),
    )
    private var server: OpenCodeTestServer? = null

    /**
     * The `Authorization` header for requests against the server started by
     * [startServer]. OpenCode 2.x always authenticates as the fixed "opencode" user.
     */
    fun authorizationHeader(): String =
        "Basic " + Base64.getEncoder().encodeToString(
            "opencode:$serverPassword".toByteArray(StandardCharsets.UTF_8),
        )

    fun startServer(timeoutMs: Long = 15_000): OpenCodeTestServer {
        check(server == null) { "Scenario environment already has a running server" }

        val port = ServerSocket(0).use { it.localPort }
        val stdoutLog = artifactsRoot.resolve("opencode-stdout.log").toFile()
        val stderrLog = artifactsRoot.resolve("opencode-stderr.log").toFile()

        val process = ProcessBuilder(opencodeBin.toString(), "serve", "--port", port.toString())
            .directory(repoRoot.toFile())
            .redirectOutput(stdoutLog)
            .redirectError(stderrLog)
            .apply {
                environment().putAll(env)
            }
            .start()

        val startedServer = OpenCodeTestServer(
            process = process,
            port = port,
            stdoutLog = stdoutLog.toPath(),
            stderrLog = stderrLog.toPath(),
        )

        try {
            waitForHealthy(startedServer, timeoutMs)
        } catch (t: Throwable) {
            startedServer.close()
            throw t
        }

        server = startedServer
        return startedServer
    }

    private fun waitForHealthy(server: OpenCodeTestServer, timeoutMs: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (!server.process.isAlive) {
                val stdout = server.stdoutLog.toFile().takeIf { it.exists() }?.readText() ?: ""
                val stderr = server.stderrLog.toFile().takeIf { it.exists() }?.readText() ?: ""
                throw IllegalStateException(
                    "OpenCode server exited before becoming healthy. stdout=[$stdout] stderr=[$stderr]",
                )
            }

            when (val result = healthApiClient.isHealthy(server.port)) {
                is ApiResult.Success -> if (result.value) return
                is ApiResult.Failure -> Unit
            }

            Thread.sleep(100)
        }

        val stdout = server.stdoutLog.toFile().takeIf { it.exists() }?.readText() ?: ""
        val stderr = server.stderrLog.toFile().takeIf { it.exists() }?.readText() ?: ""
        throw IllegalStateException(
            "Timed out waiting for OpenCode server health on port ${server.port}. stdout=[$stdout] stderr=[$stderr]",
        )
    }

    override fun close() {
        server?.close()
        server = null
        scenarioRoot.deleteRecursively()
    }

    fun diagnosticsSummary(maxLogLines: Int = 40): String = buildString {
        appendLine("scenarioRoot=$scenarioRoot")
        appendLine("repoRoot=$repoRoot")
        appendLine("artifactsRoot=$artifactsRoot")
        server?.let { startedServer ->
            appendLine("serverPort=${startedServer.port}")
            appendLine("stdoutLog=${startedServer.stdoutLog}")
            appendLine("stderrLog=${startedServer.stderrLog}")
            appendLine("stdoutTail=<<${readTail(startedServer.stdoutLog, maxLogLines)}>>")
            appendLine("stderrTail=<<${readTail(startedServer.stderrLog, maxLogLines)}>>")
        }
    }

    private fun readTail(path: Path, maxLines: Int): String {
        if (!path.exists()) return "<missing>"
        val lines = Files.readAllLines(path)
        return lines.takeLast(maxLines).joinToString("\\n")
    }

    private fun Path.deleteRecursively() {
        if (!exists()) return
        Files.walk(this).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                path.deleteIfExists()
            }
        }
    }
}

class OpenCodeTestServer(
    val process: Process,
    val port: Int,
    val stdoutLog: Path,
    val stderrLog: Path,
) : AutoCloseable {

    override fun close() {
        process.toHandle().descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
    }
}
