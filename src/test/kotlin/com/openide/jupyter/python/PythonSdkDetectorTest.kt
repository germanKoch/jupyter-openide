package com.openide.jupyter.python

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PythonSdkDetectorTest {

    @Test
    fun `subprocess checks have an effective timeout`() {
        val source = Files.createTempFile("PythonDetectorSleeper", ".java")
        Files.writeString(
            source,
            "class PythonDetectorSleeper { public static void main(String[] a) throws Exception { Thread.sleep(5000); } }"
        )
        try {
            val elapsed = measureTimeMillis {
                assertFalse(
                    PythonSdkDetector.commandSucceeds(
                        listOf(javaExecutable(), source.toAbsolutePath().toString()),
                        100,
                        TimeUnit.MILLISECONDS
                    )
                )
            }
            assertTrue(elapsed < 2_000, "Timed-out interpreter checks must not freeze the IDE")
        } finally {
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun `successful command is detected`() {
        assertTrue(
            PythonSdkDetector.commandSucceeds(
                listOf(javaExecutable(), "-version"),
                1,
                TimeUnit.SECONDS
            )
        )
    }

    private fun javaExecutable(): String {
        val executable = if (System.getProperty("os.name").startsWith("Windows", true)) {
            "java.exe"
        } else {
            "java"
        }
        return Path.of(System.getProperty("java.home"), "bin", executable).toString()
    }
}
