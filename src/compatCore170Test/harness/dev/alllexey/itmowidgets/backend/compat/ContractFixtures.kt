package dev.alllexey.itmowidgets.backend.compat

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.nio.file.Path
import kotlin.io.path.readText

/** A released Core version, compared numerically (`1.10.0` is newer than `1.7.0`). */
class CoreVersion private constructor(private val text: String) : Comparable<CoreVersion> {
    private val parts = text.split('.').map(String::toInt)

    override fun compareTo(other: CoreVersion): Int =
        parts.zip(other.parts).map { (mine, theirs) -> mine.compareTo(theirs) }.firstOrNull { it != 0 }
            ?: parts.size.compareTo(other.parts.size)

    override fun equals(other: Any?): Boolean = other is CoreVersion && parts == other.parts

    override fun hashCode(): Int = parts.hashCode()

    override fun toString(): String = text

    companion object {
        fun parse(text: String): CoreVersion = CoreVersion(text)
    }
}

/** One entry of `index.json`; `method` and `path` are set only for `http`. */
data class ContractEntry(
    val id: String,
    val kind: String,
    val method: String?,
    val path: String?,
    val file: String,
    val minCore: CoreVersion,
)

/**
 * The golden fixtures BK-03a records from Backend. The path is relative to the test task's working
 * directory, the project root; the suite's test task declares the directory as an input, so a
 * changed fixture always reruns the suite.
 */
object ContractFixtures {
    private val root: Path = Path.of("src", "test", "resources", "contract")

    val entries: List<ContractEntry> by lazy {
        JsonParser.parseString(root.resolve("index.json").readText()).asJsonArray.map { element ->
            val entry = element.asJsonObject
            ContractEntry(
                id = entry["id"].asString,
                kind = entry["kind"].asString,
                method = entry["method"].takeUnless { it.isJsonNull }?.asString,
                path = entry["path"].takeUnless { it.isJsonNull }?.asString,
                file = entry["file"].asString,
                minCore = CoreVersion.parse(entry["minCore"].asString),
            )
        }
    }

    fun entry(id: String): ContractEntry = entries.single { it.id == id }

    fun json(entry: ContractEntry): JsonElement = JsonParser.parseString(root.resolve(entry.file).readText())
}
