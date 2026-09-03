package com.redt.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.redt.R
import com.redt.distro.DistroRegistry
import com.redt.util.FileUtil
import com.redt.util.isUnder
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Exposes the Alpine rootfs as the app's single SAF root: picking "RedT"
 * in the system file picker lands directly in the Alpine filesystem root
 * (filesDir/rootfs/alpine), not in a distro-listing parent directory.
 */
class RedTDocumentsProvider : DocumentsProvider() {

    companion object {
        private const val ROOT_ID = "redt"
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
    }

    private val appContext
        get() = requireNotNull(context).applicationContext

    private val authority
        get() = "${appContext.packageName}.documents"

    /**
     * The SAF root is the Alpine rootfs itself. Never pre-created here:
     * TerminalActivity decides "Alpine installed" by this directory's
     * existence, and creating it early would break the install flow.
     */
    private val alpineRoot
        get() = File(File(appContext.filesDir, "rootfs"), DistroRegistry.alpine.name)

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_PROJECTION)
        cursor.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD,
            )
            add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(DocumentsContract.Root.COLUMN_TITLE, "RedT")
            add(DocumentsContract.Root.COLUMN_SUMMARY, "Alpine Linux rootfs")
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
            add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, appContext.filesDir.usableSpace)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        val file = resolveDocument(documentId)
        if (!FileUtil.existsWithoutFollowingLinks(file)) throw FileNotFoundException(documentId)
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
        if (!isSafeTarget(parent) || !parent.isDirectory) {
            throw FileNotFoundException(parentDocumentId)
        }
        parent.listFiles()
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            ?.forEach { includeDocument(cursor, documentIdFor(it), it) }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = resolveDocument(documentId)
        if (!FileUtil.existsWithoutFollowingLinks(file) || file.isDirectory || !isSafeTarget(file)) {
            throw FileNotFoundException(documentId)
        }
        return try {
            val parsedMode = ParcelFileDescriptor.parseMode(mode)
            if (mode.contains('w') || mode.contains('a') || mode.contains('t')) {
                ParcelFileDescriptor.open(
                    file,
                    parsedMode,
                    Handler(Looper.getMainLooper()),
                ) {
                    file.parentFile?.let { notifyChildrenChanged(documentIdFor(it)) }
                }
            } else {
                ParcelFileDescriptor.open(file, parsedMode)
            }
        } catch (e: Exception) {
            throw FileNotFoundException("Unable to open $documentId: ${e.message}")
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        validateDisplayName(displayName)
        val parent = resolveDocument(parentDocumentId)
        if (!isSafeTarget(parent) || !parent.isDirectory) {
            throw FileNotFoundException(parentDocumentId)
        }
        val file = File(parent, displayName)
        if (FileUtil.existsWithoutFollowingLinks(file)) throw IOException("$displayName already exists")

        val created = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            file.mkdir()
        } else {
            file.createNewFile()
        }
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

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return try {
            val parent = resolveDocument(parentDocumentId).absoluteFile.normalize()
            val child = resolveDocument(documentId).absoluteFile.normalize()
            child == parent || child.isUnder(parent)
        } catch (_: Exception) {
            false
        }
    }

    private fun includeDocument(cursor: MatrixCursor, documentId: String, file: File) {
        val isRoot = documentId == ROOT_DOCUMENT_ID
        val isLink = !isRoot && FileUtil.isSymlink(file)
        val safeTarget = isRoot || isSafeTarget(file)
        val isDirectory = safeTarget && file.isDirectory

        var flags = 0
        if (isDirectory && !isRoot) {
            flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        }
        if (!isDirectory && !isLink && safeTarget && file.canWrite()) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        }
        if (!isRoot && file.parentFile?.canWrite() == true) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        }

        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            add(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                if (isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else mimeTypeFor(file),
            )
            add(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                if (isRoot) "Alpine" else file.name,
            )
            add(
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                if (isLink) 0L else file.lastModified(),
            )
            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
            add(
                DocumentsContract.Document.COLUMN_SIZE,
                if (isDirectory || isLink) null else file.length(),
            )
        }
    }

    private fun resolveDocument(documentId: String): File {
        if (documentId == ROOT_DOCUMENT_ID) return alpineRoot
        if (!documentId.startsWith(DOCUMENT_PREFIX)) throw FileNotFoundException(documentId)

        val relativePath = documentId.removePrefix(DOCUMENT_PREFIX)
        val parts = relativePath.split('/')
        if (parts.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." }) {
            throw FileNotFoundException(documentId)
        }

        val file = parts.fold(alpineRoot) { parent, name -> File(parent, name) }
        val parent = file.parentFile ?: throw FileNotFoundException(documentId)
        if (!isInsideAlpine(parent.canonicalFile)) throw FileNotFoundException(documentId)
        return file
    }

    private fun resolveMutableDocument(documentId: String): File {
        if (documentId == ROOT_DOCUMENT_ID) {
            throw FileNotFoundException("The Alpine rootfs root is managed by RedT")
        }
        val file = resolveDocument(documentId)
        if (!FileUtil.existsWithoutFollowingLinks(file)) throw FileNotFoundException(documentId)
        return file
    }

    private fun documentIdFor(file: File): String {
        if (file.absoluteFile.normalize() == alpineRoot.absoluteFile.normalize()) {
            return ROOT_DOCUMENT_ID
        }
        val relative = file.absoluteFile.normalize().relativeTo(alpineRoot.absoluteFile.normalize()).path
        return "$DOCUMENT_PREFIX$relative"
    }

    private fun isInsideAlpine(file: File): Boolean {
        val rootPath = alpineRoot.canonicalFile.path
        return file.path == rootPath || file.isUnder(File(rootPath))
    }

    private fun isSafeTarget(file: File): Boolean = try {
        isInsideAlpine(file.canonicalFile)
    } catch (_: IOException) {
        false
    }

    private fun mimeTypeFor(file: File): String {
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun validateDisplayName(displayName: String) {
        if (
            displayName.isBlank() || displayName == "." || displayName == ".." ||
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
