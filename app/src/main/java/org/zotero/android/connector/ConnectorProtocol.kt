package org.zotero.android.connector

import java.security.MessageDigest
import java.security.SecureRandom

/** The mobile bridge is intentionally independent of the desktop Connector HTTP API. */
object ConnectorProtocol {
    const val PORT = 23119
    const val BASE = "/android-connector/v1"
    const val MAX_PDF_BYTES = 100L * 1024 * 1024
    const val MAX_JSON_BYTES = 1024L * 1024
    const val IDLE_MS = 5 * 60 * 1000L
    private val identifier = Regex("[A-Za-z0-9_-]{1,80}")
    fun validId(value: String) = identifier.matches(value)
    fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun secret(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    fun equal(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

class ConnectorError(val status: Int, override val message: String) : Exception(message)
