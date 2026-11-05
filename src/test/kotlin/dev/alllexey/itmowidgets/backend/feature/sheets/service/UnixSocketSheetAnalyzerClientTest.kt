package dev.alllexey.itmowidgets.backend.feature.sheets.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Talks to an in-process unix socket server that answers each connection with [reply]. */
class UnixSocketSheetAnalyzerClientTest {
    private val json = JsonMapper.builder().build()

    // macOS limits a socket path to about 100 bytes, so the directory stays under /tmp.
    private val dir: Path = Files.createTempDirectory(Path.of("/tmp"), "sa")
    private val socket: Path = dir.resolve("a.sock")
    private val requests = CopyOnWriteArrayList<String>()
    private lateinit var server: ServerSocketChannel
    private lateinit var logs: ListAppender<ILoggingEvent>
    private val logger = LoggerFactory.getLogger(UnixSocketSheetAnalyzerClient::class.java) as Logger

    @Volatile private var reply: (SocketChannel) -> Unit = { it.answer("""{"version":1,"status":"ok","fingerprint":"$FP"}""") }

    @BeforeEach
    fun start() {
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket))
        thread(isDaemon = true) {
            while (server.isOpen) {
                val channel = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) {
                    channel.use {
                        requests.add(it.readLine())
                        reply(it)
                    }
                }
            }
        }
        logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
    }

    @AfterEach
    fun stop() {
        logger.detachAppender(logs)
        server.close()
        Files.deleteIfExists(socket)
        Files.deleteIfExists(dir)
    }

    @Test
    fun `a fingerprint request is one JSON line and the answer carries the fingerprint only`() {
        val result = assertIs<AnalyzerResult.Ok>(client().call(AnalyzerOp.FINGERPRINT, SHEET))

        assertEquals(FP, result.fingerprint)
        assertNull(result.rule)
        val request = json.readTree(requests.single())
        assertEquals(1, request["version"].intValue())
        assertEquals("fingerprint", request["op"].stringValue())
        assertEquals(SHEET, request["sheet_id"].stringValue())
        assertEquals(setOf("version", "op", "sheet_id"), request.propertyNames().toSet())
    }

    @Test
    fun `an analysis returns the rule and logs neither the sheet nor the rule`() {
        reply = { it.answer("""{"version":1,"status":"ok","fingerprint":"$FP","rule":{"summary":"секретная сводка"}}""") }

        val result = assertIs<AnalyzerResult.Ok>(client().call(AnalyzerOp.ANALYZE, SHEET))

        assertEquals("секретная сводка", result.rule!!["summary"].stringValue())
        assertEquals("analyze", json.readTree(requests.single())["op"].stringValue())
        val lines = logs.list.map { it.formattedMessage }
        assertTrue(lines.single().startsWith("Sheet analyzer op=analyze status=ok code=null durationMs="), lines.single())
        assertFalse(lines.any { SHEET in it || "сводка" in it })
    }

    @Test
    fun `every analyzer failure status maps to its own code`() {
        for (status in AnalyzerStatus.FAILURES) {
            reply = { it.answer("""{"version":1,"status":"$status","code":"short-code"}""") }
            assertEquals(AnalyzerResult.Failure(status), client().call(AnalyzerOp.ANALYZE, SHEET), status)
        }
        assertTrue(logs.list.any { "op=analyze status=busy code=short-code" in it.formattedMessage })
    }

    @Test
    fun `a broken answer is a protocol failure`() {
        for (answer in listOf(
            """{"version":2,"status":"ok","fingerprint":"$FP"}""",
            """{"status":"ok","fingerprint":"$FP"}""",
            """{"version":1,"status":"exploded"}""",
            """{"version":1,"status":"ok"}""",
            """{"version":1,"status":"ok","fingerprint":"ABC"}""",
            """{"version":1,"status":"ok","fingerprint":"$FP","rule":[1]}""",
            """{"version":1,"status":"ok","fingerprint":"$FP"}""" + "x",
            "not json",
            """{"version":1,"status":"ok","status":"closed","fingerprint":"$FP"}""",
            "[]",
        )) {
            reply = { it.answer(answer) }
            assertEquals(AnalyzerResult.Failure(AnalyzerStatus.PROTOCOL), client().call(AnalyzerOp.ANALYZE, SHEET), answer)
        }
    }

    @Test
    fun `an answer without a newline or over the limit is a protocol failure`() {
        reply = { it.write(ByteBuffer.wrap("""{"version":1,"status":"busy"}""".toByteArray())) }
        assertEquals(AnalyzerResult.Failure(AnalyzerStatus.PROTOCOL), client().call(AnalyzerOp.FINGERPRINT, SHEET))

        reply =
            {
                it.answer(
                    """{"version":1,"status":"ok","fingerprint":"$FP","rule":{"x":"${"a".repeat(
                        UnixSocketSheetAnalyzerClient.MAX_RESPONSE_BYTES,
                    )}"}}""",
                )
            }
        assertEquals(AnalyzerResult.Failure(AnalyzerStatus.PROTOCOL), client().call(AnalyzerOp.ANALYZE, SHEET))
    }

    @Test
    fun `a silent analyzer times out with the timeout of the operation`() {
        reply = { Thread.sleep(2_000) }
        val started = System.nanoTime()
        assertEquals(
            AnalyzerResult.Failure(AnalyzerStatus.TIMEOUT),
            client(fingerprintTimeout = Duration.ofMillis(200)).call(AnalyzerOp.FINGERPRINT, SHEET),
        )
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofMillis(1_500))
    }

    @Test
    fun `no socket is unavailable`() {
        val missing = SheetRulesConfig(socketPath = dir.resolve("missing.sock").toString())
        assertEquals(
            AnalyzerResult.Failure(AnalyzerStatus.UNAVAILABLE),
            UnixSocketSheetAnalyzerClient(missing, json).call(AnalyzerOp.ANALYZE, SHEET),
        )
    }

    private fun client(fingerprintTimeout: Duration = Duration.ofSeconds(5)) =
        UnixSocketSheetAnalyzerClient(SheetRulesConfig(socketPath = socket.toString(), fingerprintTimeout = fingerprintTimeout), json)

    private fun SocketChannel.answer(line: String) {
        val bytes = ByteBuffer.wrap((line + "\n").toByteArray(StandardCharsets.UTF_8))
        while (bytes.hasRemaining()) write(bytes)
    }

    private fun SocketChannel.readLine(): String {
        val received = StringBuilder()
        val buffer = ByteBuffer.allocate(1)
        while (read(buffer.clear()) > 0) {
            val char = buffer.get(0).toInt().toChar()
            if (char == '\n') break
            received.append(char)
        }
        return received.toString()
    }

    private companion object {
        const val SHEET = "1AbcSyntheticSheetId_-9"
        val FP = "a".repeat(64)
    }
}
