package org.zotero.android.connector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.realm.DynamicRealm
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.kotlin.where
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.zotero.android.database.Database
import org.zotero.android.database.migration.main.MainConfigurationDbModule
import org.zotero.android.database.objects.RConnectorSave
import org.zotero.android.database.objects.RCustomLibrary
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConnectorMigrationTest {
    @Test fun upgradeAddsBookkeepingWithoutRemovingLibraryData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Realm.init(context)
        val directory = File(context.cacheDir, "connector-migration-${UUID.randomUUID()}").apply { mkdirs() }
        val old = RealmConfiguration.Builder().directory(directory).name("migration.realm")
            .modules(MainConfigurationDbModule()).schemaVersion(7).build()
        try {
            Realm.getInstance(old).use { realm ->
                realm.executeTransaction { it.createObject(RCustomLibrary::class.java, "myLibrary").orderId = 17 }
            }
            DynamicRealm.getInstance(old).use { realm ->
                realm.executeTransaction {
                    it.schema.remove("RConnectorAttachment")
                    it.schema.remove("RConnectorSave")
                }
            }
            Realm.getInstance(Database.mainConfiguration(File(directory, "migration.realm"))).use { realm ->
                assertEquals(17, realm.where<RCustomLibrary>().findFirst()!!.orderId)
                assertEquals(0L, realm.where<RConnectorSave>().count())
                realm.executeTransaction { it.createObject(RConnectorSave::class.java, "retry-id").itemKey = "ABCDEFGH" }
            }
        } finally { directory.deleteRecursively() }
    }
}
