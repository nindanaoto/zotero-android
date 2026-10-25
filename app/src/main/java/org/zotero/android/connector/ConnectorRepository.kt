package org.zotero.android.connector

import android.content.Context
import com.google.gson.JsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.realm.Realm
import io.realm.kotlin.where
import org.zotero.android.api.mappers.CreatorResponseMapper
import org.zotero.android.api.mappers.ItemResponseMapper
import org.zotero.android.api.mappers.TagResponseMapper
import org.zotero.android.database.DbWrapperMain
import org.zotero.android.database.objects.*
import org.zotero.android.database.requests.*
import org.zotero.android.files.FileStore
import org.zotero.android.sync.*
import java.io.File
import java.io.InputStream
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectorRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wrapper: DbWrapperMain,
    private val mapper: ItemResponseMapper,
    private val creators: CreatorResponseMapper,
    private val tags: TagResponseMapper,
    private val schema: SchemaController,
    private val createItem: CreateBackendItemDbRequest.Factory,
    private val createAttachment: CreateAttachmentDbRequest.Factory,
    private val files: FileStore,
    private val scheduler: SyncScheduler,
) {
    @Volatile var lastSyncError: String? = null
    private val library = LibraryIdentifier.custom(RCustomLibraryType.myLibrary)
    private fun <T> database(block: (Realm) -> T): T = Realm.getInstance(wrapper.config).use(block)
    private fun sync() = scheduler.request(SyncKind.normal, Libraries.specific(listOf(library)))

    private fun canonical(value: String): String {
        if (value.isEmpty()) return value
        val uri = try { URI(value) } catch (_: Exception) { throw ConnectorError(400, "Invalid canonical URL") }
        val host = uri.host ?: throw ConnectorError(400, "URL must have a hostname")
        if (uri.scheme !in listOf("http", "https") || uri.userInfo != null ||
            host == "kyoto-u.idm.oclc.org" || host.endsWith(".kyoto-u.idm.oclc.org")) {
            throw ConnectorError(400, "Send the publisher URL, not a proxy or credential URL")
        }
        return value
    }

    @Synchronized fun selectedCollectionKey(): String {
        if (files.getSelectedLibrary() != library) return ""
        val selected = files.getSelectedCollectionId() as? CollectionIdentifier.collection ?: return ""
        return database { realm ->
            if (realm.where<RCollection>().key(selected.key, library)
                    .equalTo("deleted", false).equalTo("trash", false).findFirst() != null) selected.key else ""
        }
    }

    @Synchronized fun collections(): List<Map<String, Any?>> = database { realm ->
        ReadCollectionsDbRequest(library, isAsync = false).process(realm)
            .map { mapOf("key" to it.key, "name" to it.name, "parentKey" to it.parentKey) }
            .sortedBy { it["name"].toString().lowercase() }
    }

    @Synchronized fun save(id: String, body: JsonObject): Map<String, Any?> {
        val fingerprint = ConnectorProtocol.sha256(body.toString())
        database { realm ->
            val existing = realm.where<RConnectorSave>().equalTo("id", id).findFirst()
            if (existing != null) {
                if (existing.fingerprint != fingerprint) throw ConnectorError(409, "Save ID already has different metadata")
                return@database
            }
            val collection = body["collectionKey"]?.takeUnless { it.isJsonNull }?.asString
            if (collection != null && realm.where<RCollection>().key(collection, library)
                    .equalTo("deleted", false).equalTo("trash", false).findFirst() == null) {
                throw ConnectorError(409, "Collection no longer exists; choose a collection again")
            }
            val raw = body.getAsJsonObject("item") ?: throw ConnectorError(400, "Missing item")
            canonical(raw["url"]?.asString ?: "")
            if (raw["itemType"]?.asString !in schema.itemTypes || raw["itemType"].asString in listOf("attachment", "note", "annotation")) {
                throw ConnectorError(400, "Unsupported paper type")
            }
            val parsed = mapper.fromTranslatorResponse(raw, schema, tags, creators)
            val item = parsed.copyWithAutomaticTags.copy(library,
                collection?.let { setOf(it) } ?: emptySet(), parsed.copyWithAutomaticTags.tags)
            val descriptors = body.getAsJsonArray("attachments") ?: throw ConnectorError(400, "Missing attachments")
            val ids = mutableSetOf<String>()
            if (descriptors.size() > 20) throw ConnectorError(400, "Too many attachments")
            for (entry in descriptors) {
                val descriptor = entry.asJsonObject
                val attachmentId = descriptor["id"].asString
                if (!ConnectorProtocol.validId(attachmentId) || !ids.add(attachmentId) ||
                    descriptor["mimeType"]?.asString != "application/pdf") throw ConnectorError(400, "Invalid PDF descriptor")
                canonical(descriptor["url"]?.asString ?: "")
            }
            realm.executeTransaction {
                createItem.create(item).process(it)
                it.createObject(RConnectorSave::class.java, id).apply {
                    itemKey = item.key
                    this.fingerprint = fingerprint
                }
                for (entry in descriptors) {
                    val descriptor = entry.asJsonObject
                    val attachmentId = descriptor["id"].asString
                    it.createObject(RConnectorAttachment::class.java, "$id:$attachmentId").apply {
                        saveId = id
                        this.attachmentId = attachmentId
                        itemKey = KeyGenerator.newKey()
                        title = descriptor["title"]?.asString ?: "Full Text PDF"
                        url = descriptor["url"]?.asString ?: ""
                    }
                }
            }
        }
        sync()
        return status(id)
    }

    @Synchronized fun receive(id: String, attachmentId: String, input: InputStream, size: Long): Map<String, Any?> {
        val incoming = File(context.filesDir, "connector-incoming").apply { mkdirs() }
        val temporary = File.createTempFile("pdf-", ".part", incoming)
        try {
            temporary.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var remaining = size
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw ConnectorError(400, "PDF transfer was interrupted")
                    out.write(buffer, 0, count)
                    remaining -= count
                }
            }
            if (!files.isPdf(temporary)) throw ConnectorError(415, "Response is not a PDF; sign in through the proxy again")
            val hash = files.md5(temporary)
            database { realm ->
                val save = realm.where<RConnectorSave>().equalTo("id", id).findFirst()
                    ?: throw ConnectorError(404, "Unknown save")
                if (realm.where<RItem>().key(save.itemKey, library).findFirst()?.deleted != false) {
                    throw ConnectorError(409, "The saved paper was deleted")
                }
                val record = realm.where<RConnectorAttachment>().equalTo("id", "$id:$attachmentId").findFirst()
                    ?: throw ConnectorError(404, "Unknown attachment")
                if (record.received) {
                    if (record.md5 != hash) throw ConnectorError(409, "Attachment ID already contains a different PDF")
                    return@database
                }
                val filename = "${record.itemKey}.pdf"
                val destination = files.attachmentFile(library, record.itemKey, filename)
                // Same private filesystem: rename is atomic. A retry can recover an orphaned file.
                if (!temporary.renameTo(destination)) throw ConnectorError(500, "Could not store PDF")
                val attachment = Attachment(
                    type = Attachment.Kind.file(filename, "application/pdf", Attachment.FileLocation.local, Attachment.FileLinkType.importedUrl),
                    title = record.title, key = record.itemKey, libraryId = library, url = record.url.ifEmpty { null },
                )
                realm.executeTransaction {
                    createAttachment.create(attachment, save.itemKey, "PDF", true, emptySet(), emptyList()).process(it)
                    record.received = true
                    record.md5 = hash
                    record.error = ""
                }
            }
        } finally {
            temporary.delete()
        }
        sync()
        return status(id)
    }

    @Synchronized fun finish(id: String, body: JsonObject): Map<String, Any?> {
        database { realm ->
            val save = realm.where<RConnectorSave>().equalTo("id", id).findFirst()
                ?: throw ConnectorError(404, "Unknown save")
            realm.executeTransaction {
                save.finished = true
                body.getAsJsonObject("errors")?.entrySet()?.forEach { (attachmentId, error) ->
                    val attachment = it.where<RConnectorAttachment>().equalTo("id", "$id:$attachmentId").findFirst()
                    if (attachment != null && !attachment.received) attachment.error = error.asString.take(500)
                }
            }
        }
        sync()
        return status(id)
    }

    @Synchronized fun status(id: String): Map<String, Any?> = database { realm ->
        val save = realm.where<RConnectorSave>().equalTo("id", id).findFirst()
            ?: throw ConnectorError(404, "Unknown save")
        val item = realm.where<RItem>().key(save.itemKey, library).findFirst()
        mapOf("saveId" to id, "itemKey" to save.itemKey, "localStored" to (item != null && !item.deleted),
            "metadataSynced" to (item != null && item.changes.isEmpty()), "finished" to save.finished,
            "syncError" to lastSyncError,
            "attachments" to realm.where<RConnectorAttachment>().equalTo("saveId", id).findAll().map { attachment ->
                val stored = realm.where<RItem>().key(attachment.itemKey, library).findFirst()
                mapOf("id" to attachment.attachmentId, "itemKey" to attachment.itemKey,
                    "received" to attachment.received,
                    "webdavUploaded" to (stored != null && !stored.attachmentNeedsSync),
                    "error" to attachment.error)
            })
    }
}
