package org.zotero.android.connector

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.zotero.android.architecture.Defaults
import org.zotero.android.database.DbWrapperMain
import org.zotero.android.sync.SchemaController
import org.zotero.android.sync.SessionController
import javax.inject.Inject

/** A separate entry point prevents launch links from being interpreted as shared files. */
@AndroidEntryPoint
class ConnectorActivity : ComponentActivity() {
    @Inject lateinit var auth: ConnectorAuth
    @Inject lateinit var defaults: Defaults
    @Inject lateinit var sessions: SessionController
    @Inject lateinit var wrapper: DbWrapperMain
    @Inject lateinit var schema: SchemaController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val message = TextView(this).apply { text = "Preparing Zotero browser saving…"; textSize = 18f }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
            addView(message)
        }
        setContentView(layout)
        if (intent.data == null) {
            message.text = "Browser saving\nPair Firefox from the Connector’s Open Zotero button."
            layout.addView(Button(this).apply {
                text = "Stop receiving and revoke pairing"
                setOnClickListener {
                    auth.clear()
                    stopService(Intent(this@ConnectorActivity, ConnectorService::class.java))
                    message.text = "Pairing revoked."
                }
            })
            return
        }
        val uri = intent.data!!
        val client = uri.getQueryParameter("clientId") ?: ""
        val challenge = uri.getQueryParameter("challenge")
        if (uri.scheme != "zotero-connector" || uri.host != "connect" || uri.getQueryParameter("v") != "1" ||
            !ConnectorProtocol.validId(client)) {
            message.text = "Invalid Connector launch link."
            return
        }
        val startReceiver: () -> Unit = {
            try {
                ContextCompat.startForegroundService(this, Intent(this, ConnectorService::class.java))
                lifecycleScope.launch {
                    delay(100) // Let onStartCommand replace a previous receiver error state.
                    withTimeoutOrNull(10000) {
                        while (ConnectorService.state == "stopped" || ConnectorService.state == "starting") delay(100)
                    }
                    if (ConnectorService.state == "ready") finish()
                    else message.text = ConnectorService.state
                }
            } catch (_: RuntimeException) {
                message.text = "Android could not start receiving. Return to Firefox and try opening Zotero again."
            }
        }
        val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startReceiver() }
        lifecycleScope.launch {
            withTimeoutOrNull(10000) {
                while (!sessions.isLoggedIn || !wrapper.isInitialized || schema.itemTypes.isEmpty()) delay(100)
            }
            if (!sessions.isLoggedIn || !wrapper.isInitialized || schema.itemTypes.isEmpty()) {
                message.text = "Open Zotero and sign in, then return to Firefox and try again."
                return@launch
            }
            if (!defaults.isWebDavEnabled()) {
                message.text = "Enable and verify WebDAV in Zotero’s account settings, then try again."
                return@launch
            }
            val start: () -> Unit = {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@ConnectorActivity,
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else startReceiver()
            }
            if (challenge != null) {
                if (!challenge.matches(Regex("[a-f0-9]{64}"))) { message.text = "Invalid pairing challenge."; return@launch }
                AlertDialog.Builder(this@ConnectorActivity).setTitle("Pair Firefox Connector?")
                    .setMessage("Allow this Connector to save papers to your personal library and sync PDFs through WebDAV?")
                    .setPositiveButton("Allow") { _, _ -> auth.approve(client, challenge); start() }
                    .setNegativeButton("Cancel") { _, _ -> finish() }.show()
            } else if (auth.paired(client)) start()
            else message.text = "Pair this Connector again from Firefox."
        }
    }
}
