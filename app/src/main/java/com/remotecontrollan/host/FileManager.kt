package com.remotecontrollan.host

import android.content.Context
import android.os.Environment
import com.remotecontrollan.model.RemoteFileItem
import com.remotecontrollan.utils.AppLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

class FileManager(private val context: Context) {

    /**
     * Standard allowed root directories:
     * Internal storage, Download, Pictures, Movies, Music, Documents
     */
    fun getStandardRoots(): List<RemoteFileItem> {
        val list = mutableListOf<RemoteFileItem>()
        val externalStorage = Environment.getExternalStorageDirectory()
        list.add(RemoteFileItem("Internal Storage", externalStorage.absolutePath, true, 0, externalStorage.lastModified()))

        val standardDirs = listOf(
            Environment.DIRECTORY_DOWNLOADS to "Downloads",
            Environment.DIRECTORY_PICTURES to "Pictures",
            Environment.DIRECTORY_MOVIES to "Movies",
            Environment.DIRECTORY_MUSIC to "Music",
            Environment.DIRECTORY_DOCUMENTS to "Documents",
            Environment.DIRECTORY_DCIM to "DCIM"
        )

        for ((type, name) in standardDirs) {
            val dir = Environment.getExternalStoragePublicDirectory(type)
            if (dir.exists()) {
                list.add(RemoteFileItem(name, dir.absolutePath, true, 0, dir.lastModified()))
            }
        }
        return list
    }

    /**
     * Lists files inside a directory. Strictly validates path to prevent directory traversal outside allowed roots.
     */
    fun listFiles(dirPath: String): List<RemoteFileItem> {
        val target = resolveAndValidate(dirPath) ?: return emptyList()
        if (!target.exists() || !target.isDirectory) return emptyList()

        val files = target.listFiles() ?: return emptyList()
        return files.map { f ->
            RemoteFileItem(
                name = f.name,
                path = f.absolutePath,
                isDirectory = f.isDirectory,
                sizeBytes = if (f.isFile) f.length() else 0,
                lastModified = f.lastModified()
            )
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun createDirectory(parentPath: String, folderName: String): Boolean {
        val parent = resolveAndValidate(parentPath) ?: return false
        val cleanName = folderName.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        if (cleanName.isBlank()) return false
        val newFolder = File(parent, cleanName)
        return newFolder.mkdirs()
    }

    fun rename(filePath: String, newName: String): Boolean {
        val file = resolveAndValidate(filePath) ?: return false
        val cleanName = newName.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        if (cleanName.isBlank()) return false
        val dest = File(file.parentFile, cleanName)
        return file.renameTo(dest)
    }

    fun delete(filePath: String): Boolean {
        val file = resolveAndValidate(filePath) ?: return false
        return if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }

    fun getInputStream(filePath: String): InputStream? {
        val file = resolveAndValidate(filePath) ?: return null
        if (!file.exists() || !file.isFile) return null
        return FileInputStream(file)
    }

    fun saveUploadedFile(parentPath: String, fileName: String, inputStream: InputStream): Boolean {
        val parent = resolveAndValidate(parentPath) ?: return false
        val cleanName = fileName.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        val dest = File(parent, cleanName)
        return try {
            FileOutputStream(dest).use { out ->
                inputStream.copyTo(out)
            }
            AppLogger.i("FileManager", "Saved uploaded file: ${dest.name}")
            true
        } catch (e: Exception) {
            AppLogger.e("FileManager", "Failed to save upload: ${e.message}", e)
            false
        }
    }

    private fun resolveAndValidate(path: String): File? {
        val file = File(path)
        val canonical = try {
            file.canonicalPath
        } catch (e: Exception) {
            return null
        }

        // Allow external storage and app-specific storage
        val ext = Environment.getExternalStorageDirectory().canonicalPath
        val appSpecific = context.filesDir.canonicalPath

        if (canonical.startsWith(ext) || canonical.startsWith(appSpecific)) {
            return File(canonical)
        }
        AppLogger.w("FileManager", "Path traversal blocked: $path")
        return null
    }
}
