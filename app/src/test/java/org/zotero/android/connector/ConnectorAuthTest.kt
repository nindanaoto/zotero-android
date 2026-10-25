package org.zotero.android.connector

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.zotero.android.architecture.Defaults

class ConnectorAuthTest {
    @Test fun pairingRequiresApprovalAndIsBoundToLoginSession() {
        val values = mutableMapOf<String, String?>()
        val context = mockk<Context>()
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        val defaults = mockk<Defaults>()
        var session = "first-session"
        every { defaults.getUserId() } returns 42L
        every { defaults.getSessionId() } answers { session }
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
        every { prefs.contains(any()) } answers { values.containsKey(firstArg()) }
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers { values[firstArg()] = secondArg(); editor }
        every { editor.clear() } answers { values.clear(); editor }
        every { editor.commit() } returns true
        val auth = ConnectorAuth(context, defaults)
        val verifier = ConnectorProtocol.secret()
        assertThrows(ConnectorError::class.java) { auth.exchange("client", verifier) }
        auth.approve("client", ConnectorProtocol.sha256(verifier))
        assertThrows(ConnectorError::class.java) { auth.exchange("other-client", verifier) }
        assertThrows(ConnectorError::class.java) { auth.exchange("client", "wrong verifier") }
        val token = auth.exchange("client", verifier)
        assertEquals(token, auth.exchange("client", verifier))
        assertTrue(auth.authorized("Bearer $token"))
        assertFalse(auth.authorized("Bearer wrong"))
        session = "second-session"
        assertFalse(auth.authorized("Bearer $token"))
        auth.clear()
        assertFalse(auth.paired("client"))
        assertThrows(ConnectorError::class.java) { auth.exchange("client", verifier) }
    }

    @Test fun protocolDoesNotAllowPathTraversalOrAmbiguousIds() {
        assertTrue(ConnectorProtocol.validId("paper_123-abc"))
        for (id in listOf("", "../paper", "paper/attachment", "a".repeat(81), "paper?x=1")) {
            assertFalse(ConnectorProtocol.validId(id))
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ConnectorProtocol.sha256("abc"))
    }
}
