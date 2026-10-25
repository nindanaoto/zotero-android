# Android Connector receiver

This package receives metadata and authenticated PDF downloads from the Firefox
Android Connector fork. The browser owns university login and download requests;
the app owns Zotero credentials, collection selection data, local items, and WebDAV
sync. It does not implement the desktop Connector HTTP protocol.

## Build and test

```sh
./gradlew :app:assembleDevDebug :app:testDevDebugUnitTest
./gradlew :app:compileDevDebugAndroidTestKotlin
# With a connected emulator or device:
./gradlew :app:connectedDevDebugAndroidTest
```

The APK is `app/build/outputs/apk/dev/debug/app-dev-debug.apk`. Configure the debug
app with your Zotero account and verified WebDAV settings before pairing Firefox.
Use the same Zotero account and WebDAV server on desktop for the round-trip test.

## Protocol v1

The receiver binds IPv4 loopback on port 23119. All paths start with
`/android-connector/v1`. Responses are JSON; errors contain `error` and use an HTTP
error status. Successful imports acknowledge durable local receipt, independently
of network synchronization.

| Method and path | Input / result |
| --- | --- |
| `POST /pair` | Approved `{clientId, verifier}` → bearer token |
| `GET /status` | Protocol version, WebDAV readiness, sync activity |
| `GET /collections` | Personal collections: key, name, parentKey |
| `PUT /saves/{saveId}` | `{item, collectionKey, attachments}` → receipt |
| `PUT /saves/{saveId}/attachments/{attachmentId}` | Raw `application/pdf` bytes → receipt |
| `POST /saves/{saveId}/finish` | `{errors: {attachmentId: message}}` → receipt |
| `GET /saves/{saveId}` | Current local and sync status |

`item` uses translator JSON. Each expected PDF has `id`, `title`, `mimeType`, and
canonical `url`. Publisher fetch URLs, proxy cookies, and university credentials
remain in Firefox. Imports require WebDAV and target only the personal library.
JSON requests are limited to 1 MiB, PDFs to 100 MiB. Binary requests use
Content-Length, not chunked encoding. The server streams files to private storage.

The exported activity accepts `zotero-connector://connect?v=1&clientId=...`.
For first pairing, Firefox adds a SHA-256 `challenge`, and the user approves it in
the app. A two-minute verifier exchange produces an account/session-bound token;
subsequent calls use `Authorization: Bearer ...`. Ordinary website origins and
non-loopback Host headers are rejected. The app exposes no permissive CORS API.

The foreground receiver has a Stop action, idle timeout, and Android platform
timeout handling. Logout revokes pairing and stops it. Settings → Browser saving
also stops receiving and revokes the current pairing.

## Persistence and recovery

Realm schema 8 adds local-only `RConnectorSave` and `RConnectorAttachment` records.
The save-to-item mapping is committed in the same transaction as the parent item;
attachment receipt is committed with the attachment row. Identical retries reuse
keys, while conflicting metadata or PDF content returns 409. Restarted transfers
replace only the temporary/uncommitted file, never a committed attachment.

Normal sync uploads metadata and then sends attachments through the existing
WebDAV controller. Connector-owned attachments are blocked from falling back to
Zotero Storage if WebDAV is disabled. Offline files remain local and dirty for the
next normal sync or app/receiver startup. No separate background retry scheduler
is introduced. After Android kills the receiver, Firefox must reopen the app to
restart receiving.

Unit tests exercise real HTTP routing and account-bound pairing. The on-device
migration test verifies preservation of existing library data. Device acceptance
must verify app switching and authenticated Kyoto proxy downloads, interruption
and retry, WebDAV authentication/network failures, and a desktop WebDAV download.
