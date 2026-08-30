package com.openide.jupyter.analysis

import org.junit.jupiter.api.Assumptions.assumeFalse
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertTrue

class NotebookAnalyzerTest {

    @Test
    fun `successful analyzer subprocess returns diagnostics`() {
        assumeFalse(isWindows(), "POSIX executable fixture")
        val fast = executableScript("cat >/dev/null\nprintf '{\"diagnostics\":[]}'")
        val analyzer = NotebookAnalyzer({ fast }, null, 1_000)
        val completed = CountDownLatch(1)
        try {
            analyzer.analyze(listOf("fast" to "pass")) { completed.countDown() }
            assertTrue(completed.await(2, TimeUnit.SECONDS))
        } finally {
            analyzer.dispose()
            Files.deleteIfExists(java.nio.file.Path.of(fast))
        }
    }

    @Test
    fun `hung analyzer is killed and does not block the next analysis`() {
        assumeFalse(isWindows(), "POSIX executable fixture")
        val slow = executableScript("exec sleep 5")
        val fast = executableScript("cat >/dev/null\nprintf '{\"diagnostics\":[]}'")
        val executable = AtomicReference(slow)
        val analyzer = NotebookAnalyzer(
            pythonPathProvider = { executable.get() },
            workingDirectory = null,
            processTimeoutMillis = 750
        )

        try {
            // Larger than a typical OS pipe buffer: the old synchronous stdin
            // write blocked before waitFor(timeout) could even begin.
            analyzer.analyze(listOf("slow" to "x".repeat(2_000_000))) {}
            Thread.sleep(250)
            executable.set(fast)

            val completed = CountDownLatch(1)
            analyzer.analyze(listOf("fast" to "pass")) { completed.countDown() }

            assertTrue(
                completed.await(4, TimeUnit.SECONDS),
                "A timed-out analyzer process must not occupy the single worker indefinitely"
            )
        } finally {
            analyzer.dispose()
            Files.deleteIfExists(java.nio.file.Path.of(slow))
            Files.deleteIfExists(java.nio.file.Path.of(fast))
        }
    }

    private fun executableScript(body: String): String {
        val path = Files.createTempFile("notebook-analyzer-test-", ".sh")
        Files.writeString(path, "#!/bin/sh\n$body\n")
        assertTrue(path.toFile().setExecutable(true))
        return path.toAbsolutePath().toString()
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
