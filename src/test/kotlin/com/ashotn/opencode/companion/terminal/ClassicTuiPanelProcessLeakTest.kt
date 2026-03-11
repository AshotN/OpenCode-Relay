package com.ashotn.opencode.companion.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Verifies that [ClassicTuiPanel] kills the terminal process on [ClassicTuiPanel.stop]
 * and [ClassicTuiPanel.dispose].
 *
 * Uses [ClassicTuiPanel.processOverride] to inject a real `sleep 3600` OS process
 * without needing live terminal infrastructure. Assertions are purely OS-level PID checks.
 */
class ClassicTuiPanelProcessLeakTest : BasePlatformTestCase() {

    fun `test stop kills the terminal process`() {
        val process = spawnSleepProcess()
        val pid = process.pid()
        val panel = ClassicTuiPanel(project, testRootDisposable, processOverride = process)

        ApplicationManager.getApplication().invokeAndWait { panel.startIfNeeded() }
        assertTrue("pre: process must be alive (pid=$pid)", isAlive(pid))

        panel.stop()

        waitForDeath(pid)
        assertFalse("process must be dead after stop() (pid=$pid)", isAlive(pid))
    }

    fun `test dispose kills the terminal process`() {
        val process = spawnSleepProcess()
        val pid = process.pid()
        val panelDisposable = Disposer.newDisposable()
        val panel = ClassicTuiPanel(project, panelDisposable, processOverride = process)

        ApplicationManager.getApplication().invokeAndWait { panel.startIfNeeded() }
        assertTrue("pre: process must be alive (pid=$pid)", isAlive(pid))

        Disposer.dispose(panelDisposable) // triggers panel.dispose() → tearDown()

        waitForDeath(pid)
        assertFalse("process must be dead after dispose() (pid=$pid)", isAlive(pid))
    }

    fun `test reset cycle kills both the old and the new terminal process`() {
        val process1 = spawnSleepProcess()
        val pid1 = process1.pid()
        val process2 = spawnSleepProcess()
        val pid2 = process2.pid()

        // Use a fresh panel per cycle via processOverride on each startIfNeeded call.
        // First session: inject process1.
        val panel = ClassicTuiPanel(project, testRootDisposable, processOverride = process1)
        ApplicationManager.getApplication().invokeAndWait { panel.startIfNeeded() }
        assertTrue("pre cycle 1: process1 alive (pid=$pid1)", isAlive(pid1))

        panel.stop()
        waitForDeath(pid1)
        assertFalse("cycle 1: process1 must be dead (pid=$pid1)", isAlive(pid1))

        // Second session: swap in process2 via a new panel (panel.stop() cleared terminalWidget).
        val panel2 = ClassicTuiPanel(project, testRootDisposable, processOverride = process2)
        ApplicationManager.getApplication().invokeAndWait { panel2.startIfNeeded() }
        assertTrue("pre cycle 2: process2 alive (pid=$pid2)", isAlive(pid2))

        panel2.stop()
        waitForDeath(pid2)
        assertFalse("cycle 2: process2 must be dead (pid=$pid2)", isAlive(pid2))

        assertFalse("cycle 1 process must still be dead (pid=$pid1)", isAlive(pid1))
    }

    // ---- helpers ----

    private fun spawnSleepProcess(): Process =
        ProcessBuilder("sleep", "3600").redirectErrorStream(true).start()

    private fun isAlive(pid: Long): Boolean =
        ProcessHandle.of(pid).orElse(null)?.isAlive ?: false

    private fun waitForDeath(pid: Long, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (isAlive(pid) && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }
}
