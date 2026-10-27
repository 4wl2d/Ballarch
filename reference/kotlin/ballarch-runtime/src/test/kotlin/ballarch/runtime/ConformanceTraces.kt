package ballarch.runtime

import ballarch.testkit.Harness
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Sample conformance traces (`tools/TRACE-FORMAT.md`) written by the
 * exploration tests, and the cross-check that `tools/oracles.py`, the
 * language-neutral checker, reports exactly what [ballarch.testkit.Oracles.check]
 * reports. Without python3 the cross-check is skipped with a message.
 */
object ConformanceTraces {
    /** `build/conformance-traces` of this module; sample traces of clean runs go here. */
    val directory: Path = Path.of(System.getProperty("ballarch.traceDir") ?: "build/conformance-traces")

    /** Seeded-violation traces go here, apart from the samples, which must all pass. */
    val seeded: Path = directory.resolve("seeded")

    private val script: Path? = System.getProperty("ballarch.oracles")?.let { Path.of(it) }?.takeIf { Files.isRegularFile(it) }

    /** Why the cross-check cannot run here, or `null` if it can. */
    val unavailable: String? by lazy {
        when {
            script == null -> "tools/oracles.py not found (system property ballarch.oracles=${System.getProperty("ballarch.oracles")})"
            !pythonRuns() -> "python3 is not on the PATH"
            else -> null
        }
    }

    private fun pythonRuns(): Boolean = try {
        val p = ProcessBuilder("python3", "--version").redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (e: IOException) {
        false
    }

    /** Keeps the first run of each variant of a scenario: writes its trace and remembers the Kotlin verdict. */
    class Sampler(private val scenario: String) {
        private val written = LinkedHashMap<Path, List<String>>()

        fun offer(variant: String, harness: Harness) {
            val file = directory.resolve("$scenario--$variant.jsonl")
            if (file in written) return
            harness.writeTrace(file)
            written[file] = harness.violations()
        }

        /** Checks every written trace with tools/oracles.py. Returns the number of traces written. */
        fun crossCheck(): Int {
            assertPythonAgrees(written)
            return written.size
        }
    }

    /** Asserts that tools/oracles.py reports, for each file, the messages Oracles.check gave for it (as T instead of R). */
    fun assertPythonAgrees(expected: Map<Path, List<String>>) {
        unavailable?.let {
            println("Skipping the cross-check of tools/oracles.py against Oracles.check: $it")
            return
        }
        if (expected.isEmpty()) return
        val reported = runPython(expected.keys)
        for ((file, kotlin) in expected) {
            assertEquals(
                kotlin.map(::asTraceProperty).sorted(),
                reported[file].orEmpty().sorted(),
                "tools/oracles.py and Oracles.check disagree on $file",
            )
        }
    }

    /** Oracles.check names rules (`R5: ...`); the trace format names the matching properties (`T5: ...`). */
    fun asTraceProperty(message: String): String = RULE.replace(message) { "${it.groupValues[1]}T${it.groupValues[2]}:" }

    private val RULE = Regex("""^((?:\[epoch \d+] )?)R(\d+):""")

    /** Runs tools/oracles.py on [files] and returns the violation messages it printed for each. */
    private fun runPython(files: Collection<Path>): Map<Path, List<String>> {
        val command = listOf("python3", script.toString(), "--quiet") + files.map { it.toString() }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readLines()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("tools/oracles.py did not finish")
        }
        val byFile = files.associateWith { ArrayList<String>() }
        for (line in output) {
            val file = files.firstOrNull { line.startsWith("$it: ") } ?: fail("unexpected output of tools/oracles.py: $line\n" + output.joinToString("\n"))
            byFile.getValue(file) += line.removePrefix("$file: ")
        }
        val expectedStatus = if (byFile.values.any { it.isNotEmpty() }) 1 else 0
        assertEquals(expectedStatus, process.exitValue(), "exit status of tools/oracles.py:\n" + output.joinToString("\n"))
        return byFile
    }
}
