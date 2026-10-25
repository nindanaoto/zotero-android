package org.zotero.android.database.migration.main

import io.realm.DynamicRealm
import io.realm.RealmMigration
import io.realm.FieldAttribute
import org.zotero.android.database.migration.main.solutions.MigrateAllItemsDbRowTypeIconNameTypeChange
import org.zotero.android.database.migration.main.solutions.MigrateExtractAnnotationTypeFromItems
import org.zotero.android.database.migration.main.solutions.MigrateMarkAllNonLocalGroupsAsOutdatedToTriggerResync
import org.zotero.android.database.migration.main.solutions.MigrateNonArabicFormattingSortIndex
import org.zotero.android.database.migration.main.solutions.MigrateRecentlyRead

internal class MainDbMigration(private val fileName: String) : RealmMigration {

    override fun migrate(dynamicRealm: DynamicRealm, oldVersion: Long, newVersion: Long) {
        if (oldVersion < 8) {
            dynamicRealm.schema.create("RConnectorSave")
                .addField("id", String::class.java, FieldAttribute.REQUIRED, FieldAttribute.PRIMARY_KEY)
                .addField("itemKey", String::class.java, FieldAttribute.REQUIRED)
                .addField("fingerprint", String::class.java, FieldAttribute.REQUIRED)
                .addField("finished", Boolean::class.javaPrimitiveType!!)
            dynamicRealm.schema.create("RConnectorAttachment")
                .addField("id", String::class.java, FieldAttribute.REQUIRED, FieldAttribute.PRIMARY_KEY)
                .addField("saveId", String::class.java, FieldAttribute.REQUIRED)
                .addField("attachmentId", String::class.java, FieldAttribute.REQUIRED)
                .addField("itemKey", String::class.java, FieldAttribute.REQUIRED)
                .addField("title", String::class.java, FieldAttribute.REQUIRED)
                .addField("url", String::class.java, FieldAttribute.REQUIRED)
                .addField("md5", String::class.java, FieldAttribute.REQUIRED)
                .addField("error", String::class.java, FieldAttribute.REQUIRED)
                .addField("received", Boolean::class.javaPrimitiveType!!)
        }
        if (oldVersion < 2) {
            MigrateAllItemsDbRowTypeIconNameTypeChange(dynamicRealm).migrate()
        }
        if (oldVersion < 3) {
            MigrateMarkAllNonLocalGroupsAsOutdatedToTriggerResync(dynamicRealm).migrate()
        }
        if (oldVersion < 4) {
            MigrateExtractAnnotationTypeFromItems(dynamicRealm).migrate()
        }
        if (oldVersion < 6) {
            MigrateNonArabicFormattingSortIndex(dynamicRealm).migrate()
        }
        if (oldVersion < 7) {
            MigrateRecentlyRead(dynamicRealm).migrate()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MainDbMigration

        return fileName == other.fileName
    }

    override fun hashCode(): Int {
        return fileName.hashCode()
    }

}
