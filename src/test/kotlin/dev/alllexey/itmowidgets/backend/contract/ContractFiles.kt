package dev.alllexey.itmowidgets.backend.contract

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The fixture directory in the source tree, so a recording run writes where Git sees it.
 * `-Pcontract.record=true` (passed through by `tasks.test`) switches from checking to recording.
 */
object ContractFiles {
    val root: Path = Path.of("src", "test", "resources", "contract").toAbsolutePath()
    val recording: Boolean = System.getProperty("contract.record") == "true"

    private const val RECORD_HINT =
        "scripts/verify.sh run -- test --tests 'dev.alllexey.itmowidgets.backend.contract.*' -Pcontract.record=true"

    fun exists(relative: String): Boolean = root.resolve(relative).isRegularFile()

    fun read(relative: String): ByteArray = root.resolve(relative).also {
        assertTrue(it.exists(), "Missing fixture $relative; record it with $RECORD_HINT")
    }.readBytes()

    /**
     * Checks [actual] against the fixture, or records it. A recording run writes only a missing or
     * semantically different file, so every unchanged fixture stays byte for byte.
     */
    fun check(relative: String, actual: JsonNode) {
        val file = root.resolve(relative)
        val expected = if (file.exists()) ContractJson.parse(file.readBytes()) else null
        val differences = expected?.let { ContractJson.differences(it, actual) }
        if (recording) {
            if (differences == null || differences.isNotEmpty()) write(file, actual)
            return
        }
        if (differences == null) fail("Missing fixture $relative; record it with $RECORD_HINT")
        if (differences.isNotEmpty()) {
            fail("$relative drifted from the recorded contract:\n" + differences.joinToString("\n") { "  $it" } +
                "\nA wire change needs the compatibility rule of docs/contracts; only an added route or optional" +
                " field is re-recorded ($RECORD_HINT).")
        }
    }

    /** Writes [actual] only when the file is missing; used for request bodies, which old clients freeze. */
    fun create(relative: String, actual: JsonNode) {
        val file = root.resolve(relative)
        if (recording && !file.exists()) write(file, actual)
    }

    /** Every fixture file below [directory], relative to [root], with `/` separators. */
    fun list(directory: String): List<String> {
        val start = root.resolve(directory)
        if (!start.exists()) return emptyList()
        return Files.walk(start).use { paths ->
            paths.filter { it.isRegularFile() }.map { root.relativize(it).invariantSeparatorsPathString }.toList()
        }.sorted()
    }

    private fun write(file: Path, actual: JsonNode) {
        Files.createDirectories(file.parent)
        file.writeText(ContractJson.render(actual))
    }
}
