package org.zotero.android.connector

import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.zotero.android.architecture.Defaults
import org.zotero.android.database.DbWrapperMain
import org.zotero.android.sync.SchemaController
import org.zotero.android.sync.SessionController
import org.zotero.android.sync.SyncScheduler
import java.net.HttpURLConnection
import java.net.URL

class ConnectorServerTest {
    private val auth = mockk<ConnectorAuth>()
    private val repository = mockk<ConnectorRepository>()
    private val defaults = mockk<Defaults>()
    private val sessions = mockk<SessionController>()
    private val wrapper = mockk<DbWrapperMain>()
    private val schema = mockk<SchemaController>()
    private val scheduler = mockk<SyncScheduler>()
    private lateinit var server: ConnectorServer

    @Before fun start() {
        every { auth.authorized(any()) } answers { firstArg<String?>() == "Bearer test-token" }
        every { sessions.isLoggedIn } returns true
        every { wrapper.isInitialized } returns true
        every { schema.itemTypes } returns listOf("journalArticle")
        every { defaults.isWebDavEnabled() } returns true
        every { scheduler.inProgress } returns MutableStateFlow(false)
        server = ConnectorServer(auth, repository, defaults, sessions, wrapper, schema, scheduler, port = 0)
        server.start(5000, true)
    }
    @After fun stop() { server.stop() }

    private fun request(path: String, method: String = "GET", body: ByteArray? = null,
                        token: Boolean = true, origin: String? = null, contentType: String = "application/json"): Pair<Int, String> {
        // HttpURLConnection silently strips Origin, so use a real raw HTTP request for that case.
        if (origin != null) return java.net.Socket("127.0.0.1", server.listeningPort).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write(("GET ${ConnectorProtocol.BASE}$path HTTP/1.1\r\n" +
                "Host: 127.0.0.1:${server.listeningPort}\r\nOrigin: $origin\r\n" +
                "Authorization: Bearer test-token\r\nConnection: close\r\n\r\n").toByteArray())
            val response = socket.getInputStream().bufferedReader().readText()
            response.lineSequence().first().split(' ')[1].toInt() to response
        }
        val connection = URL("http://127.0.0.1:${server.listeningPort}${ConnectorProtocol.BASE}$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            if (token) connection.setRequestProperty("Authorization", "Bearer test-token")
            if (origin != null) connection.setRequestProperty("Origin", origin)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            return status to (if (status >= 400) connection.errorStream else connection.inputStream).bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    @Test fun requestsNeedPairingAndRejectWebsiteOrigins() {
        assertEquals(401, request("/collections", token = false).first)
        assertEquals(403, request("/collections", origin = "https://publisher.example").first)
        verify { repository wasNot Called }
    }

    @Test fun webdavDisabledDoesNotAcceptAnImport() {
        every { defaults.isWebDavEnabled() } returns false
        assertEquals(409, request("/saves/paper", "PUT", "{}".toByteArray()).first)
        verify { repository wasNot Called }
    }

    @Test fun statusNegotiatesVersionAndPdfIsDeliveredAsBinary() {
        assertTrue(request("/status").second.contains("\"protocolVersion\":1"))
        val pdf = "%PDF-1.7\nexample".toByteArray()
        every { repository.receive("paper", "pdf-0", any(), pdf.size.toLong()) } answers {
            assertArrayEquals(pdf, thirdArg<java.io.InputStream>().readNBytes(pdf.size))
            mapOf("localStored" to true)
        }
        assertEquals(200, request("/saves/paper/attachments/pdf-0", "PUT", pdf, contentType = "application/pdf").first)
        assertEquals(415, request("/saves/paper/attachments/pdf-0", "PUT", pdf, contentType = "text/html").first)
    }

    @Test fun malformedMetadataIsRejectedWithoutCallingTheRepository() {
        assertEquals(400, request("/saves/paper", "PUT", "{".toByteArray()).first)
        verify { repository wasNot Called }
    }

    @Test fun oversizedPdfIsRejectedBeforeReadingItsBody() {
        java.net.Socket("127.0.0.1", server.listeningPort).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write(("PUT ${ConnectorProtocol.BASE}/saves/paper/attachments/pdf-0 HTTP/1.1\r\n" +
                "Host: 127.0.0.1:${server.listeningPort}\r\nAuthorization: Bearer test-token\r\n" +
                "Content-Type: application/pdf\r\nContent-Length: ${ConnectorProtocol.MAX_PDF_BYTES + 1}\r\nConnection: close\r\n\r\n").toByteArray())
            assertTrue(socket.getInputStream().bufferedReader().readLine().contains("413"))
        }
        verify { repository wasNot Called }
    }
}
