package org.zotero.android.connector

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.zotero.android.architecture.Defaults
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectorAuth @Inject constructor(
    @ApplicationContext context: Context,
    private val defaults: Defaults,
) {
    private val prefs = context.getSharedPreferences("android-connector", Context.MODE_PRIVATE)
    private var challenge: String? = null
    private var expires = 0L
    private var pendingClient: String? = null

    // Binding to the login session prevents an old pairing from surviving logout/relogin.
    private fun account() = "${defaults.getUserId()}:${defaults.getSessionId()}"
    @Synchronized fun paired(client: String): Boolean = prefs.getString("account", null) == account() &&
        prefs.getString("client", null) == client && prefs.contains("token")

    @Synchronized fun approve(client: String, hash: String) {
        require(ConnectorProtocol.validId(client) && hash.matches(Regex("[a-f0-9]{64}")))
        pendingClient = client
        challenge = hash
        expires = System.currentTimeMillis() + 120_000
    }

    @Synchronized fun exchange(client: String, verifier: String): String {
        if (client != pendingClient || System.currentTimeMillis() > expires ||
            !ConnectorProtocol.equal(ConnectorProtocol.sha256(verifier), challenge ?: "")) {
            throw ConnectorError(401, "Pairing was not approved or has expired")
        }
        // Repeating an exchange after a lost response returns the same token.
        val token = if (paired(client)) prefs.getString("token", null)!! else ConnectorProtocol.secret()
        prefs.edit().putString("account", account()).putString("client", client)
            .putString("token", token).commit()
        return token
    }

    @Synchronized fun authorized(header: String?): Boolean {
        val token = prefs.getString("token", null) ?: return false
        return prefs.getString("account", null) == account() &&
            ConnectorProtocol.equal(header ?: "", "Bearer $token")
    }

    @Synchronized fun clear() {
        prefs.edit().clear().commit()
        challenge = null
        pendingClient = null
        expires = 0
    }
}
