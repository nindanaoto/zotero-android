package org.zotero.android.database.objects

import io.realm.RealmObject
import io.realm.annotations.PrimaryKey

/** Local bookkeeping only; these records are never sent to the Zotero API. */
open class RConnectorSave : RealmObject() {
    @PrimaryKey var id: String = ""
    var itemKey: String = ""
    var fingerprint: String = ""
    var finished: Boolean = false
}

open class RConnectorAttachment : RealmObject() {
    @PrimaryKey var id: String = ""
    var saveId: String = ""
    var attachmentId: String = ""
    var itemKey: String = ""
    var title: String = ""
    var url: String = ""
    var md5: String = ""
    var error: String = ""
    var received: Boolean = false
}
