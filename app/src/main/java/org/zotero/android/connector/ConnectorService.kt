package org.zotero.android.connector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import org.zotero.android.architecture.Defaults
import org.zotero.android.database.DbWrapperMain
import org.zotero.android.sync.SchemaController
import org.zotero.android.sync.SessionController
import org.zotero.android.sync.SyncScheduler
import org.zotero.android.architecture.navigation.toolbar.data.SyncProgress
import org.zotero.android.architecture.navigation.toolbar.data.SyncProgressEventStream
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class ConnectorService : Service() {
    @Inject lateinit var auth: ConnectorAuth
    @Inject lateinit var repository: ConnectorRepository
    @Inject lateinit var defaults: Defaults
    @Inject lateinit var sessions: SessionController
    @Inject lateinit var wrapper: DbWrapperMain
    @Inject lateinit var schema: SchemaController
    @Inject lateinit var scheduler: SyncScheduler
    @Inject lateinit var progress: SyncProgressEventStream
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ConnectorServer? = null

    companion object {
        @Volatile var state: String = "stopped"
            private set
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (server != null) { server?.lastActivity = System.currentTimeMillis(); return START_NOT_STICKY }
        if (state == "starting") return START_NOT_STICKY
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(
            "connector", "Browser saving", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, ConnectorService::class.java).setAction("stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val settings = PendingIntent.getActivity(this, 2, Intent(this, ConnectorActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(23119, NotificationCompat.Builder(this, "connector")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Zotero browser saving")
            .setContentText("Ready to receive papers from Firefox").setContentIntent(settings)
            .setOngoing(true).addAction(0, "Stop receiving", stop).build())
        state = "starting"
        val account = "${defaults.getUserId()}:${defaults.getSessionId()}"
        scope.launch {
            try {
                File(filesDir, "connector-incoming").listFiles()?.forEach { it.delete() }
                val receiver = ConnectorServer(auth, repository, defaults, sessions, wrapper, schema, scheduler)
                receiver.start(120_000, false)
                server = receiver
                state = "ready"
                launch {
                    progress.flow().collect { event ->
                        when (event) {
                            is SyncProgress.finished -> repository.lastSyncError = if (event.errors.isEmpty()) null
                                else "Sync needs attention. Open Zotero to check your account and WebDAV settings."
                            is SyncProgress.aborted -> repository.lastSyncError = "Sync failed. Your paper remains saved locally; open Zotero to retry."
                            else -> Unit
                        }
                    }
                }
                while (isActive) {
                    delay(1000)
                    if (!sessions.isLoggedIn || account != "${defaults.getUserId()}:${defaults.getSessionId()}") {
                        auth.clear()
                        break
                    }
                    if (receiver.requests.get() == 0 && !scheduler.inProgress.value &&
                        System.currentTimeMillis() - receiver.lastActivity > ConnectorProtocol.IDLE_MS) break
                }
            } catch (_: Exception) {
                state = "Could not start receiving. The local port may already be in use."
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
    override fun onDestroy() {
        server?.stop()
        server = null
        scope.cancel()
        if (state == "ready" || state == "starting") state = "stopped"
        super.onDestroy()
    }
}
