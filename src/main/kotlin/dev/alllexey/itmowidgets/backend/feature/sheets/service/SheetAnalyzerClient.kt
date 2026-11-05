package dev.alllexey.itmowidgets.backend.feature.sheets.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.time.Duration

enum class AnalyzerOp(val wire: String) { FINGERPRINT("fingerprint"), ANALYZE("analyze") }

/** What one analyzer call ended with. Every failure is a [code] of the wire `reason` set, never analyzer text. */
sealed interface AnalyzerResult {
    /** [rule] is set only for [AnalyzerOp.ANALYZE]; it is untrusted until `SheetRuleShape` accepts it. */
    class Ok(val fingerprint: String, val rule: JsonNode?) : AnalyzerResult {
        override fun toString(): String = "Ok(redacted)"
    }

    data class Failure(val code: String) : AnalyzerResult
}

/** The analyzer's own failure statuses (protocol version 1); they become reason codes as they are. */
object AnalyzerStatus {
    const val CLOSED = "closed"
    const val TOO_LARGE = "too_large"
    const val NOT_FOUND = "not_found"
    const val NETWORK = "network"
    const val BUSY = "busy"
    const val MODEL_FAILED = "model_failed"
    const val REJECTED = "rejected"
    const val INSUFFICIENT = "insufficient"

    val FAILURES = setOf(CLOSED, TOO_LARGE, NOT_FOUND, NETWORK, BUSY, MODEL_FAILED, REJECTED, INSUFFICIENT)

    /** Backend's own: no socket, no answer in time, a broken answer. */
    const val UNAVAILABLE = "unavailable"
    const val TIMEOUT = "timeout"
    const val PROTOCOL = "protocol"
}

/** One request per connection to the host's `sheet-analyzer` (ADR 0038). */
interface SheetAnalyzerClient {
    fun call(op: AnalyzerOp, sheetId: String): AnalyzerResult
}

/**
 * Newline-framed JSON over the unix socket of `itmowidgets.sheet-rules.socket-path`: one request line, one response
 * line of at most [MAX_RESPONSE_BYTES], then the connection closes. Logs carry the operation, the status, the
 * analyzer's short code and the duration only: never the sheet id or the rule.
 */
@Service
class UnixSocketSheetAnalyzerClient(private val config: SheetRulesConfig, private val jsonMapper: JsonMapper) : SheetAnalyzerClient {
    // The application mapper tolerates trailing content for old clients; an analyzer answer is exactly one value.
    private val strict = jsonMapper.reader()
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    override fun call(op: AnalyzerOp, sheetId: String): AnalyzerResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Sheet analyzer I/O requires no transaction" }
        val started = System.nanoTime()
        val timeout = if (op == AnalyzerOp.ANALYZE) config.analyzeTimeout else config.fingerprintTimeout
        val (result, code) = exchange(op, sheetId, timeout)
        val durationMs = (System.nanoTime() - started) / 1_000_000
        val status = if (result is AnalyzerResult.Failure) result.code else "ok"
        if (result is AnalyzerResult.Ok || result.isExpected()) {
            logger.info("Sheet analyzer op={} status={} code={} durationMs={}", op.wire, status, code, durationMs)
        } else {
            logger.warn("Sheet analyzer op={} status={} code={} durationMs={}", op.wire, status, code, durationMs)
        }
        return result
    }

    private fun AnalyzerResult.isExpected() =
        this is AnalyzerResult.Failure && code in setOf(AnalyzerStatus.CLOSED, AnalyzerStatus.NOT_FOUND, AnalyzerStatus.INSUFFICIENT)

    /** The result and the analyzer's short code for the log. */
    private fun exchange(op: AnalyzerOp, sheetId: String, timeout: Duration): Pair<AnalyzerResult, String?> {
        val line = try {
            roundTrip(request(op, sheetId), timeout)
        } catch (_: AnalyzerTimeout) {
            return failure(AnalyzerStatus.TIMEOUT)
        } catch (_: AnalyzerUnavailable) {
            return failure(AnalyzerStatus.UNAVAILABLE)
        } catch (_: AnalyzerProtocolError) {
            return failure(AnalyzerStatus.PROTOCOL)
        } catch (_: IOException) {
            // The connection broke after it was made.
            return failure(AnalyzerStatus.PROTOCOL)
        }
        val node = try {
            strict.readTree(line)
        } catch (_: JacksonException) {
            return failure(AnalyzerStatus.PROTOCOL)
        }
        return parse(op, node)
    }

    private fun parse(op: AnalyzerOp, node: JsonNode): Pair<AnalyzerResult, String?> {
        if (!node.isObject || !node.path("version").isIntegralNumber || node.path("version").intValue() != VERSION) {
            return failure(AnalyzerStatus.PROTOCOL)
        }
        val status = node.path("status").takeIf { it.isString }?.stringValue() ?: return failure(AnalyzerStatus.PROTOCOL)
        val code = node.path("code").takeIf { it.isString }?.stringValue()?.takeIf { CODE.matches(it) }
        if (status != "ok") return (if (status in AnalyzerStatus.FAILURES) AnalyzerResult.Failure(status) else protocol()) to code
        val fingerprint = node.path("fingerprint").takeIf { it.isString }?.stringValue()
            ?.takeIf { FINGERPRINT.matches(it) } ?: return failure(AnalyzerStatus.PROTOCOL)
        if (op == AnalyzerOp.FINGERPRINT) return AnalyzerResult.Ok(fingerprint, null) to null
        val rule = node.get("rule")?.takeIf { it.isObject } ?: return failure(AnalyzerStatus.PROTOCOL)
        return AnalyzerResult.Ok(fingerprint, rule) to null
    }

    private fun request(op: AnalyzerOp, sheetId: String): ByteArray {
        val body = jsonMapper.createObjectNode().put("version", VERSION).put("op", op.wire).put("sheet_id", sheetId)
        return (jsonMapper.writeValueAsString(body) + "\n").toByteArray(StandardCharsets.UTF_8)
    }

    /** Writes [request] and reads up to the first newline before the deadline. */
    private fun roundTrip(request: ByteArray, timeout: Duration): String {
        val deadline = System.nanoTime() + timeout.toNanos()
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            try {
                channel.connect(UnixDomainSocketAddress.of(config.socketPath))
            } catch (_: IOException) {
                throw AnalyzerUnavailable()
            }
            channel.configureBlocking(false)
            Selector.open().use { selector ->
                val output = ByteBuffer.wrap(request)
                val key = channel.register(selector, SelectionKey.OP_WRITE)
                while (output.hasRemaining()) {
                    await(selector, deadline)
                    channel.write(output)
                }
                key.interestOps(SelectionKey.OP_READ)
                val received = ByteArrayOutputStream()
                val input = ByteBuffer.allocate(BUFFER_BYTES)
                while (true) {
                    await(selector, deadline)
                    input.clear()
                    val read = channel.read(input)
                    if (read < 0) throw AnalyzerProtocolError()
                    for (index in 0 until read) {
                        val byte = input.get(index)
                        if (byte == NEWLINE) return received.toString(StandardCharsets.UTF_8)
                        if (received.size() >= MAX_RESPONSE_BYTES) throw AnalyzerProtocolError()
                        received.write(byte.toInt())
                    }
                }
            }
        }
    }

    private fun await(selector: Selector, deadline: Long) {
        while (true) {
            val left = Duration.ofNanos(deadline - System.nanoTime())
            if (left <= Duration.ZERO) throw AnalyzerTimeout()
            // select(0) would wait forever.
            if (selector.select(maxOf(1L, left.toMillis())) > 0) {
                selector.selectedKeys().clear()
                return
            }
        }
    }

    private fun failure(code: String): Pair<AnalyzerResult, String?> = AnalyzerResult.Failure(code) to null

    private fun protocol() = AnalyzerResult.Failure(AnalyzerStatus.PROTOCOL)

    private class AnalyzerTimeout : RuntimeException()

    private class AnalyzerUnavailable : RuntimeException()

    private class AnalyzerProtocolError : RuntimeException()

    companion object {
        const val VERSION = 1
        const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val BUFFER_BYTES = 8192
        private const val NEWLINE = '\n'.code.toByte()
        private val FINGERPRINT = Regex("^[0-9a-f]{64}$")
        private val CODE = Regex("^[A-Za-z0-9_.:-]{1,64}$")
        private val logger = LoggerFactory.getLogger(UnixSocketSheetAnalyzerClient::class.java)
    }
}
