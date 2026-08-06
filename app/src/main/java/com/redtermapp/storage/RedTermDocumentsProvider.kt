package com.redtermapp.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import com.redtermapp.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class RedTermDocumentsProvider : DocumentsProvider() {

    companion object {
        private const val ROOT_ID = "redterm"
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

    private val rootfsDir
        get() = File(appContext.filesDir, "rootfs")

    override fun onCreate(): Boolean {
        rootfsDir.mkdirs()
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_PROJECTION)
        val installedCount = installedDistros().size
        cursor.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD,
            )
            add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(DocumentsContract.Root.COLUMN_TITLE, "RedTerm")
            add(
                DocumentsContract.Root.COLUMN_SUMMARY,
                "$installedCount installed distribution${if (installedCount == 1) "" else "s"}",
            )
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
            add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, appContext.filesDir.usableSpace)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        val file = resolveDocument(documentId)
        if (!existsWithoutFollowingLinks(file)) throw FileNotFoundException(documentId)
        includeDocument(cursor, documentId, file)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        if (parentDocumentId == ROOT_DOCUMENT_ID) {
            installedDistros().forEach { name ->
                val distroDir = File(rootfsDir, name)
                if (distroDir.isDirectory && isSafeTarget(distroDir)) {
                    includeDocument(cursor, documentIdFor(distroDir), distroDir)
                }
            }
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
        if (!existsWithoutFollowingLinks(file) || file.isDirectory || !isSafeTarget(file)) {
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
        if (parentDocumentId == ROOT_DOCUMENT_ID) {
            throw FileNotFoundException("Files cannot be created above installed distributions")
        }
        validateDisplayName(displayName)
        val parent = resolveDocument(parentDocumentId)
        if (!isSafeTarget(parent) || !parent.isDirectory) {
            throw FileNotFoundException(parentDocumentId)
        }
        val file = File(parent, displayName)
        if (existsWithoutFollowingLinks(file)) throw IOException("$displayName already exists")

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
        if (!deleteWithoutFollowingLinks(file)) throw IOException("Unable to delete ${file.name}")
        notifyChildrenChanged(parentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        validateDisplayName(displayName)
        val file = resolveMutableDocument(documentId)
        val parent = requireNotNull(file.parentFile)
        val renamed = File(parent, displayName)
        if (existsWithoutFollowingLinks(renamed)) throw IOException("$displayName already exists")
        if (!file.renameTo(renamed)) throw IOException("Unable to rename ${file.name}")
        notifyChildrenChanged(documentIdFor(parent))
        return documentIdFor(renamed)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return try {
            val parent = resolveDocument(parentDocumentId)
            val child = resolveDocument(documentId)
            val parentPath = parent.absoluteFile.normalize().path
            val childPath = child.absoluteFile.normalize().path
            childPath == parentPath || childPath.startsWith(parentPath + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    private fun includeDocument(cursor: MatrixCursor, documentId: String, file: File) {
        val isRoot = documentId == ROOT_DOCUMENT_ID
        val isLink = !isRoot && isSymbolicLink(file)
        val safeTarget = isRoot || isSafeTarget(file)
        val isDirectory = safeTarget && file.isDirectory
        val protectedDistroRoot = isDistroRoot(documentId)

        var flags = 0
        if (isDirectory && !isRoot) {
            flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        }
        if (!isDirectory && !isLink && safeTarget && file.canWrite()) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        }
        if (!isRoot && !protectedDistroRoot && file.parentFile?.canWrite() == true) {
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
                if (isRoot) "RedTerm" else file.name,
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
        if (documentId == ROOT_DOCUMENT_ID) return rootfsDir
        if (!documentId.startsWith(DOCUMENT_PREFIX)) throw FileNotFoundException(documentId)

        val relativePath = documentId.removePrefix(DOCUMENT_PREFIX)
        val parts = relativePath.split('/')
        if (parts.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." }) {
            throw FileNotFoundException(documentId)
        }
        if (parts.first() !in installedDistros()) throw FileNotFoundException(documentId)

        val file = parts.fold(rootfsDir) { parent, name -> File(parent, name) }
        val parent = file.parentFile ?: throw FileNotFoundException(documentId)
        if (!isInsideRootfs(parent.canonicalFile)) throw FileNotFoundException(documentId)
        return file
    }

    private fun resolveMutableDocument(documentId: String): File {
        if (documentId == ROOT_DOCUMENT_ID || isDistroRoot(documentId)) {
            throw FileNotFoundException("Installed distribution roots are managed by RedTerm")
        }
        val file = resolveDocument(documentId)
        if (!existsWithoutFollowingLinks(file)) throw FileNotFoundException(documentId)
        return file
    }

    private fun documentIdFor(file: File): String {
        if (file.absoluteFile.normalize() == rootfsDir.absoluteFile.normalize()) {
            return ROOT_DOCUMENT_ID
        }
        val relative = file.absoluteFile.normalize().relativeTo(rootfsDir.absoluteFile.normalize()).path
        return "$DOCUMENT_PREFIX$relative"
    }

    private fun installedDistros(): List<String> {
        val installedDir = File(appContext.filesDir, "installed")
        return installedDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.isNotEmpty() && '/' !in it.name }
            ?.map { it.name }
            ?.filter { File(rootfsDir, it).isDirectory }
            ?.sorted()
            ?.toList()
            ?: emptyList()
    }

    private fun isDistroRoot(documentId: String): Boolean {
        if (!documentId.startsWith(DOCUMENT_PREFIX)) return false
        return '/' !in documentId.removePrefix(DOCUMENT_PREFIX)
    }

    private fun isInsideRootfs(file: File): Boolean {
        val rootPath = rootfsDir.canonicalFile.path
        val filePath = file.path
        return filePath == rootPath || filePath.startsWith(rootPath + File.separator)
    }

    private fun isSafeTarget(file: File): Boolean = try {
        isInsideRootfs(file.canonicalFile)
    } catch (_: IOException) {
        false
    }

    private fun isSymbolicLink(file: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)
    } catch (_: Exception) {
        false
    }

    private fun existsWithoutFollowingLinks(file: File): Boolean =
        file.exists() || isSymbolicLink(file)

    private fun deleteWithoutFollowingLinks(file: File): Boolean {
        if (isSymbolicLink(file) || !file.isDirectory) return file.delete()
        val children = file.listFiles() ?: return false
        for (child in children) {
            if (!deleteWithoutFollowingLinks(child)) return false
        }
        return file.delete()
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
