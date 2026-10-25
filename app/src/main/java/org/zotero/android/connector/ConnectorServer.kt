package org.zotero.android.connector

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import org.zotero.android.architecture.Defaults
import org.zotero.android.database.DbWrapperMain
import org.zotero.android.sync.SchemaController
import org.zotero.android.sync.SessionController
import org.zotero.android.sync.SyncScheduler
import java.util.concurrent.atomic.AtomicInteger

class ConnectorServer(
    private val auth: ConnectorAuth,
    private val repository: ConnectorRepository,
    private val defaults: Defaults,
    private val sessionController: SessionController,
    private val wrapper: DbWrapperMain,
    private val schema: SchemaController,
    private val scheduler: SyncScheduler,
    port: Int = ConnectorProtocol.PORT,
) : NanoHTTPD("127.0.0.1", port) {
    private val gson = Gson()
    @Volatile var lastActivity = System.currentTimeMillis()
    val requests = AtomicInteger(0)

    override fun serve(session: IHTTPSession): Response {
        requests.incrementAndGet()
        try {
            val host = session.headers["host"]
            if (host != "127.0.0.1:$listeningPort") throw ConnectorError(403, "Invalid host")
            val origin = session.headers["origin"]
            if (origin != null && !origin.matches(Regex("moz-extension://[a-fA-F0-9-]+"))) {
                throw ConnectorError(403, "Website access is not permitted")
            }
            val path = session.uri.removePrefix(ConnectorProtocol.BASE).split('/').filter { it.isNotEmpty() }
            if (!session.uri.startsWith("${ConnectorProtocol.BASE}/")) throw ConnectorError(404, "Unknown endpoint")
            if (!sessionController.isLoggedIn || !wrapper.isInitialized || schema.itemTypes.isEmpty()) {
                throw ConnectorError(503, "Open Zotero and sign in first")
            }
            if (path == listOf("pair") && session.method == Method.POST) {
                val body = json(session)
                return response(200, mapOf("token" to auth.exchange(body["clientId"].asString, body["verifier"].asString)))
            }
            if (!auth.authorized(session.headers["authorization"])) throw ConnectorError(401, "Pair this Connector with Zotero")
            if (path == listOf("status") && session.method == Method.GET) {
                return response(200, mapOf("protocolVersion" to 1, "storageMode" to if (defaults.isWebDavEnabled()) "webdav" else "disabled",
                    "syncInProgress" to scheduler.inProgress.value))
            }
            if (!defaults.isWebDavEnabled()) throw ConnectorError(409, "Configure WebDAV in Zotero before saving")
            if (path == listOf("collections") && session.method == Method.GET) return response(200, repository.collections())
            if (path.size < 2 || path[0] != "saves" || !ConnectorProtocol.validId(path[1])) throw ConnectorError(404, "Unknown endpoint")
            val id = path[1]
            val result: Any = when {
                path.size == 2 && session.method == Method.GET -> repository.status(id)
                path.size == 2 && session.method == Method.PUT -> repository.save(id, json(session))
                path.size == 3 && path[2] == "finish" && session.method == Method.POST -> repository.finish(id, json(session))
                path.size == 4 && path[2] == "attachments" && ConnectorProtocol.validId(path[3]) && session.method == Method.PUT -> {
                    if (session.headers["content-type"]?.substringBefore(';') != "application/pdf") throw ConnectorError(415, "Expected PDF bytes")
                    repository.receive(id, path[3], session.inputStream, length(session, ConnectorProtocol.MAX_PDF_BYTES))
                }
                else -> throw ConnectorError(404, "Unknown endpoint")
            }
            if (session.method != Method.GET) lastActivity = System.currentTimeMillis()
            return response(200, result)
        } catch (error: ConnectorError) {
            return response(error.status, mapOf("error" to error.message))
        } catch (_: IllegalArgumentException) {
            return response(400, mapOf("error" to "Invalid request"))
        } catch (_: NullPointerException) {
            return response(400, mapOf("error" to "Missing request fields"))
        } catch (_: Exception) {
            // Do not put publisher URLs, bearer tokens, or submitted metadata into logs.
            return response(500, mapOf("error" to "Could not import the paper; retry or check Zotero"))
        } finally {
            requests.decrementAndGet()
        }
    }

    private fun length(session: IHTTPSession, limit: Long): Long {
        if (session.headers.containsKey("transfer-encoding")) throw ConnectorError(400, "Chunked requests are not supported")
        val size = session.headers["content-length"]?.toLongOrNull() ?: throw ConnectorError(411, "Content-Length required")
        if (size <= 0 || size > limit) throw ConnectorError(413, "Request exceeds the size limit")
        return size
    }

    private fun json(session: IHTTPSession): JsonObject {
        if (session.headers["content-type"]?.substringBefore(';') != "application/json") throw ConnectorError(415, "Expected JSON")
        val size = length(session, ConnectorProtocol.MAX_JSON_BYTES).toInt()
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = session.inputStream.read(bytes, offset, size - offset)
            if (count < 0) throw ConnectorError(400, "Incomplete request")
            offset += count
        }
        return try { JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject }
        catch (_: Exception) { throw ConnectorError(400, "Invalid JSON object") }
    }

    private fun response(status: Int, value: Any): Response = newFixedLengthResponse(
        Response.Status.lookup(status) ?: Response.Status.INTERNAL_ERROR,
        "application/json", gson.toJson(value)
    ).apply {
        addHeader("Cache-Control", "no-store")
        closeConnection(true)
    }
}
