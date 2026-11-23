package dev.alllexey.itmowidgets.backend.feature.push.service

import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpTransport
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseOptions
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** FCM answers with `Content-Encoding: gzip`; the transport Firebase uses has to hand the JSON over once decoded. */
class FirebaseTransportTest {
    private val credentials = GoogleCredentials.create(AccessToken("synthetic", null))

    @Test fun `the Firebase transport reads a gzip answer`() {
        assertEquals("""{"name":"projects/synthetic/messages/1"}""", readGzipAnswer(firebaseOptions(credentials).httpTransport))
    }

    /** Documents why [firebaseOptions] sets a transport: with the managed HttpClient 5 the default one cannot. */
    @Test fun `the default Firebase transport fails on the same answer`() {
        val default = FirebaseOptions.builder().setCredentials(credentials).build().httpTransport
        assertFailsWith<ZipException> { readGzipAnswer(default) }
    }

    private fun readGzipAnswer(transport: HttpTransport): String {
        val body = ByteArrayOutputStream().also { bytes ->
            GZIPOutputStream(bytes).use { it.write("""{"name":"projects/synthetic/messages/1"}""".toByteArray()) }
        }.toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = GenericUrl("http://127.0.0.1:${server.address.port}/v1/projects/synthetic/messages:send")
            return transport.createRequestFactory().buildPostRequest(url, null).execute().parseAsString()
        } finally {
            server.stop(0)
        }
    }
}
