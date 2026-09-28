package alpiner.app.storage

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import alpiner.app.R
import alpiner.app.distro.Alpine
import alpiner.app.util.FileUtil
import alpiner.app.util.isUnder
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Exposes the Alpine rootfs (filesDir/rootfs/alpine) as the app's single SAF root. */
class DocumentsProvider : android.provider.DocumentsProvider() {

    companion object {
        private const val ROOT_ID = "alpiner"
        private const val ROOT_DOCUMENT_ID = "root"
        private const val DOCUMENT_PREFIX = "$ROOT_DOCUMENT_ID/"

        private val ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        )

        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        /** Notifies SAF clients that the root list or its children changed (install, cleanup). */
        fun notifyRootsChanged(context: Context) {
            val authority = authorityOf(context)
            context.contentResolver.notifyChange(
                DocumentsContract.buildRootsUri(authority),
                null,
            )
            context.contentResolver.notifyChange(
                DocumentsContract.buildChildDocumentsUri(authority, ROOT_DOCUMENT_ID),
                null,
            )
        }

        private fun authorityOf(context: Context) = "${context.packageName}.documents"
    }

    private val appContext
        get() = requireNotNull(context).applicationContext

    private val authority
        get() = authorityOf(appContext)

    private val alpineRoot
        get() = Alpine.rootfsDir(appContext)

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_PROJECTION)
        cursor.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
            add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(DocumentsContract.Root.COLUMN_TITLE, "Alpiner")
            add(DocumentsContract.Root.COLUMN_SUMMARY, "Alpine Linux rootfs")
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
            add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, appContext.filesDir.usableSpace)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val file = resolveDocument(documentId)
        if (!FileUtil.existsWithoutFollowingLinks(file)) throw FileNotFoundException(documentId)
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        includeDocument(cursor, documentId, file)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        // Alpine not installed yet: show an empty root instead of failing.
        if (parentDocumentId == ROOT_DOCUMENT_ID && !FileUtil.existsWithoutFollowingLinks(alpineRoot)) {
            return cursor
        }
        val parent = resolveDocument(parentDocumentId)
        val root = canonicalRoot() ?: throw FileNotFoundException(parentDocumentId)
        if (!isInsideRoot(parent, root) || !parent.isDirectory) throw FileNotFoundException(parentDocumentId)
        parent.listFiles()
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            ?.forEach { includeDocument(cursor, documentIdFor(it), it, root) }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = resolveDocument(documentId)
        if (!FileUtil.existsWithoutFollowingLinks(file) || file.isDirectory || !isInsideRoot(file)) {
            throw FileNotFoundException(documentId)
        }
        return try {
            val parsedMode = ParcelFileDescriptor.parseMode(mode)
            if (mode.any { it == 'w' || it == 'a' || it == 't' }) {
                ParcelFileDescriptor.open(file, parsedMode, Handler(Looper.getMainLooper())) {
                    file.parentFile?.let { notifyChildrenChanged(documentIdFor(it)) }
                }
            } else {
                ParcelFileDescriptor.open(file, parsedMode)
            }
        } catch (e: Exception) {
            throw FileNotFoundException("Unable to open $documentId: ${e.message}")
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        validateDisplayName(displayName)
        val parent = resolveDocument(parentDocumentId)
        if (!isInsideRoot(parent) || !parent.isDirectory) throw FileNotFoundException(parentDocumentId)
        val file = File(parent, displayName)
        if (FileUtil.existsWithoutFollowingLinks(file)) throw IOException("$displayName already exists")
        val created = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile()
        if (!created) throw IOException("Unable to create $displayName")
        notifyChildrenChanged(parentDocumentId)
        return documentIdFor(file)
    }

    override fun deleteDocument(documentId: String) {
        val file = resolveMutableDocument(documentId)
        val parentId = documentIdFor(requireNotNull(file.parentFile))
        if (!FileUtil.deleteTreeWithoutFollowingLinks(file)) throw IOException("Unable to delete ${file.name}")
        notifyChildrenChanged(parentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        validateDisplayName(displayName)
        val file = resolveMutableDocument(documentId)
        val parent = requireNotNull(file.parentFile)
        val renamed = File(parent, displayName)
        if (FileUtil.existsWithoutFollowingLinks(renamed)) throw IOException("$displayName already exists")
        if (!file.renameTo(renamed)) throw IOException("Unable to rename ${file.name}")
        notifyChildrenChanged(documentIdFor(parent))
        return documentIdFor(renamed)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        val parent = resolveDocument(parentDocumentId).absoluteFile.normalize()
        val child = resolveDocument(documentId).absoluteFile.normalize()
        child == parent || child.isUnder(parent)
    } catch (_: Exception) {
        false
    }

    private fun includeDocument(
        cursor: MatrixCursor,
        documentId: String,
        file: File,
        canonicalRoot: File? = null,
    ) {
        val isRoot = documentId == ROOT_DOCUMENT_ID
        val isLink = !isRoot && FileUtil.isSymlink(file)
        val safeTarget = isRoot || isInsideRoot(file, canonicalRoot ?: canonicalRoot())
        val isDirectory = safeTarget && file.isDirectory

        var flags = 0
        if (isDirectory && !isRoot) flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        if (!isDirectory && !isLink && safeTarget && file.canWrite()) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        }
        if (!isRoot && file.parentFile?.canWrite() == true) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        }

        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            add(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                if (isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else mimeTypeFor(file),
            )
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, if (isRoot) "Alpine" else file.name)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, if (isLink) 0L else file.lastModified())
            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
            add(DocumentsContract.Document.COLUMN_SIZE, if (isDirectory || isLink) null else file.length())
        }
    }

    private fun resolveDocument(documentId: String): File {
        if (documentId == ROOT_DOCUMENT_ID) return alpineRoot
        if (!documentId.startsWith(DOCUMENT_PREFIX)) throw FileNotFoundException(documentId)
        val parts = documentId.removePrefix(DOCUMENT_PREFIX).split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) throw FileNotFoundException(documentId)
        val file = parts.fold(alpineRoot) { parent, name -> File(parent, name) }
        val parent = file.parentFile ?: throw FileNotFoundException(documentId)
        if (!isInsideRoot(parent)) throw FileNotFoundException(documentId)
        return file
    }

    private fun resolveMutableDocument(documentId: String): File {
        if (documentId == ROOT_DOCUMENT_ID) {
            throw FileNotFoundException("The Alpine rootfs root is managed by Alpiner")
        }
        val file = resolveDocument(documentId)
        if (!FileUtil.existsWithoutFollowingLinks(file)) throw FileNotFoundException(documentId)
        return file
    }

    private fun documentIdFor(file: File): String {
        val root = alpineRoot.absoluteFile.normalize()
        val normalized = file.absoluteFile.normalize()
        if (normalized == root) return ROOT_DOCUMENT_ID
        return DOCUMENT_PREFIX + normalized.relativeTo(root).path
    }

    private fun canonicalRoot(): File? = try {
        alpineRoot.canonicalFile
    } catch (_: IOException) {
        null
    }

    /** True when [file] (after canonicalization) is the root itself or beneath it. */
    private fun isInsideRoot(file: File, canonicalRoot: File? = canonicalRoot()): Boolean {
        if (canonicalRoot == null) return false
        return try {
            val canonical = file.canonicalFile
            canonical == canonicalRoot || canonical.isUnder(canonicalRoot)
        } catch (_: IOException) {
            false
        }
    }

    private fun mimeTypeFor(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"

    private fun validateDisplayName(displayName: String) {
        if (displayName.isBlank() || displayName == "." || displayName == ".." ||
            '/' in displayName || '\u0000' in displayName
        ) {
            throw FileNotFoundException("Invalid file name")
        }
    }

    private fun notifyChildrenChanged(parentDocumentId: String) {
        appContext.contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId),
            null,
        )
    }
}
