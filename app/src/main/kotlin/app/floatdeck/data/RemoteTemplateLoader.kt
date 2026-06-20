package app.floatdeck.data

import android.content.Context
import android.util.Log
import app.floatdeck.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Remote template pack loader.
 * Downloads a ZIP from a URL, validates it, and extracts it into the app's private storage.
 *
 * Security-critical logic (path resolution, id validation, bounded copy,
 * extraction/copy) lives in the [companion] and does not depend on [Context],
 * so it can be unit-tested directly.
 */
class RemoteTemplateLoader(
    private val context: Context,
) {
    /** Thrown when the template already exists; the UI should ask whether to overwrite. */
    class TemplateExistsException(
        val templateId: String,
        message: String,
        messageResId: Int = 0,
        args: Array<out Any> = emptyArray(),
    ) : TemplateLoadException(message, messageResId, args)

    /** Thrown when the user tries to download over HTTP (unencrypted); the UI should ask them to confirm. */
    class HttpProtocolException(
        message: String,
    ) : TemplateLoadException(message)

    /** Force-overwrite flag, set by the UI layer. */
    var allowOverwrite = false

    companion object {
        private const val TAG = "RemoteTemplateLoader"

        // Template storage directory
        internal const val TEMPLATE_DIR = "remote_templates"

        // Max ZIP size: 50MB
        internal const val MAX_ZIP_SIZE = 50L * 1024 * 1024

        // Max size of a single extracted file: 10MB
        internal const val MAX_FILE_SIZE = 10L * 1024 * 1024

        // Max total extracted size: 200MB (ZIP-bomb defense)
        internal const val MAX_TOTAL_EXTRACTED = 200L * 1024 * 1024

        // Allowed image extensions
        internal val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

        // Allowed file extensions (images + json)
        internal val ALLOWED_EXTENSIONS = IMAGE_EXTENSIONS + "json"

        private val TEMPLATE_ID_REGEX = Regex("[a-zA-Z0-9_-]+")

        /**
         * Safely resolves [childName] under [baseDir], returning null if the resulting path
         * escapes [baseDir] (path traversal). The canonical-path comparison also defends
         * against `..` and symlink-based bypasses.
         */
        internal fun safeResolveChild(baseDir: File, childName: String): File? {
            if (childName.isBlank()) return null
            // Reject absolute paths (Unix) and Windows drive letters.
            if (childName.startsWith("/") || childName.startsWith("\\")) return null
            if (childName.length >= 2 && childName[1] == ':') return null
            val baseCanonical = baseDir.canonicalFile
            val resolved = File(baseCanonical, childName).canonicalFile
            val basePath = baseCanonical.path
            if (resolved.path != basePath &&
                !resolved.path.startsWith(basePath + File.separator)
            ) {
                return null
            }
            return resolved
        }

        /** Template id may only contain letters, digits, underscores and hyphens. */
        internal fun isValidTemplateId(id: String): Boolean = id.matches(TEMPLATE_ID_REGEX)

        /**
         * Copies this stream to [out], throwing [IOException] once more than [maxBytes] have been
         * written. Defends against decompression bombs that lie about their declared size
         * ([ZipEntry] size is attacker-controlled; actual bytes are what count).
         * Note: does NOT close the input stream — the caller owns its lifecycle (e.g. a
         * [ZipInputStream] that must keep iterating).
         */
        internal fun InputStream.copyToWithLimit(out: OutputStream, maxBytes: Long): Long {
            var written = 0L
            val buffer = ByteArray(8192)
            while (true) {
                val read = read(buffer)
                if (read < 0) break
                written += read
                if (written > maxBytes) {
                    throw IOException("Exceeded maximum allowed size of $maxBytes bytes")
                }
                out.write(buffer, 0, read)
            }
            out.flush()
            return written
        }

        /**
         * Validates the ZIP and extracts it into [templateDir]. Does not depend on Context, for testability.
         * @param overwrite whether to overwrite if the template already exists.
         * @return the imported template id.
         */
        @JvmOverloads
        internal fun extractTemplateFromZip(
            zipFile: File,
            templateDir: File,
            overwrite: Boolean = false,
        ): String {
            try {
                ZipInputStream(zipFile.inputStream().buffered()).use { zipInputStream ->
                    var templateJson: JSONObject? = null
                    var templateId: String? = null
                    var zipFolderName: String? = null
                    val entries = mutableListOf<ZipEntry>()

                    // First pass: scan all entries.
                    var entry: ZipEntry? = zipInputStream.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        Log.d(TAG, "Scanning ZIP entry: $name")

                        // Reject absolute paths; the canonical-path check runs in the second pass.
                        if (name.startsWith("/") || name.startsWith("\\")) {
                            throw TemplateLoadException(
                                "ZIP contains illegal path: $name",
                                R.string.error_illegal_path,
                                arrayOf(name),
                            )
                        }

                        if (entry.isDirectory) {
                            entry = zipInputStream.nextEntry
                            continue
                        }

                        // ZIP structure requires: folder/file (exactly one level of directory).
                        val slashCount = name.count { it == '/' }
                        if (slashCount == 0) {
                            throw TemplateLoadException(
                                "ZIP structure error: files must be inside a subfolder",
                                R.string.error_zip_structure,
                            )
                        }
                        if (slashCount > 1) {
                            Log.w(TAG, "Ignoring nested entry: $name")
                            entry = zipInputStream.nextEntry
                            continue
                        }

                        val folderName = name.substringBefore("/")
                        val fileName = name.substringAfter("/")

                        if (fileName.isEmpty()) {
                            entry = zipInputStream.nextEntry
                            continue
                        }

                        val extension =
                            fileName.substringAfterLast(".", "").lowercase()
                        if (extension !in ALLOWED_EXTENSIONS) {
                            throw TemplateLoadException(
                                "Unsupported file type: $fileName",
                                R.string.error_unsupported_file_type,
                                arrayOf(fileName),
                            )
                        }

                        // Declared size is only a quick pre-check; the authoritative cap is the streamed
                        // byte count in the second pass.
                        if (entry.size > MAX_FILE_SIZE) {
                            throw TemplateLoadException(
                                "File too large: $fileName (max 10MB)",
                                R.string.error_single_file_too_large,
                                arrayOf(fileName),
                            )
                        }

                        if (fileName == "template.json") {
                            zipFolderName = folderName
                            val content = zipInputStream.readBytes().toString(Charsets.UTF_8)
                            templateJson =
                                try {
                                    JSONObject(content)
                                } catch (e: Exception) {
                                    throw TemplateLoadException(
                                        "Invalid template.json: ${e.message}",
                                        R.string.error_template_json_invalid_detail,
                                        arrayOf(e.message ?: ""),
                                    )
                                }

                            templateId = templateJson.optString("id")
                            if (templateId.isNullOrBlank()) {
                                throw TemplateLoadException(
                                    "template.json is missing the id field",
                                    R.string.error_template_no_id,
                                )
                            }
                            if (!isValidTemplateId(templateId)) {
                                throw TemplateLoadException(
                                    "Template id may only contain letters, digits, underscores and hyphens",
                                    R.string.error_invalid_template_id,
                                )
                            }
                            if (!templateJson.has("wallpaper")) {
                                throw TemplateLoadException(
                                    "template.json is missing the wallpaper field",
                                    R.string.error_template_no_wallpaper,
                                )
                            }
                            if (!templateJson.has("portraits")) {
                                throw TemplateLoadException(
                                    "template.json is missing the portraits field",
                                    R.string.error_template_no_portraits,
                                )
                            }
                            Log.d(TAG, "Found template.json: id=$templateId, folder=$zipFolderName")
                        }

                        entries.add(entry)
                        entry = zipInputStream.nextEntry
                    }

                    if (templateJson == null) {
                        val allNames = entries.map { it.name }
                        Log.e(TAG, "template.json not found. Entries: $allNames")
                        throw TemplateLoadException(
                            "template.json not found in ZIP",
                            R.string.error_template_json_missing,
                        )
                    }

                    // Verify that every image referenced by template.json is present in the ZIP.
                    val fileNames =
                        entries.map { it.name.substringAfterLast("/") }.toSet()
                    Log.d(TAG, "Template files: $fileNames")

                    val wallpaper = templateJson.getString("wallpaper")
                    if (wallpaper !in fileNames) {
                        throw TemplateLoadException(
                            "Missing wallpaper file: $wallpaper",
                            R.string.error_missing_wallpaper,
                            arrayOf(wallpaper),
                        )
                    }

                    val portraits = templateJson.getJSONObject("portraits")
                    for (side in listOf("left", "right")) {
                        val arr = portraits.optJSONArray(side) ?: continue
                        for (i in 0 until arr.length()) {
                            val file = arr.getJSONObject(i).getString("file")
                            if (file !in fileNames) {
                                throw TemplateLoadException(
                                    "Missing portrait file: $file",
                                    R.string.error_missing_portrait,
                                    arrayOf(file),
                                )
                            }
                        }
                    }

                    val safeId = templateId!!
                    // Second pass: extract to the target dir (authoritative path-traversal + size checks).
                    return writeZipEntries(
                        zipFile,
                        resolveTargetDir(templateDir, safeId, overwrite),
                        entries.map { it.name },
                    )
                }
            } catch (e: TemplateLoadException) {
                Log.e(TAG, "Template validation failed: ${e.message}", e)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "ZIP processing failed: ${e.message}", e)
                throw TemplateLoadException(
                    "ZIP processing failed: ${e.message}",
                    R.string.error_zip_failed,
                    arrayOf(e.message ?: ""),
                )
            }
        }

        /** Second-pass extraction: enforce canonical-path validation and streamed byte counting per entry. */
        private fun writeZipEntries(zipFile: File, targetDir: File, entryNames: List<String>): String {
            var totalExtracted = 0L
            val allowed = entryNames.map { it.substringAfter("/") }.toSet()
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var e: ZipEntry? = zis.nextEntry
                while (e != null) {
                    if (e.isDirectory) {
                        e = zis.nextEntry
                        continue
                    }
                    val slashCnt = e.name.count { it == '/' }
                    if (slashCnt != 1) {
                        e = zis.nextEntry
                        continue
                    }
                    val fn = e.name.substringAfter("/")
                    if (fn.isEmpty() || fn !in allowed) {
                        e = zis.nextEntry
                        continue
                    }

                    val outFile = safeResolveChild(targetDir, fn)
                        ?: throw TemplateLoadException(
                            "ZIP contains illegal path: ${e.name}",
                            R.string.error_illegal_path,
                            arrayOf(e.name),
                        )

                    Log.d(TAG, "Extracting: $fn")
                    FileOutputStream(outFile).use { output ->
                        val written = zis.copyToWithLimit(output, MAX_FILE_SIZE)
                        totalExtracted += written
                        if (totalExtracted > MAX_TOTAL_EXTRACTED) {
                            throw TemplateLoadException(
                                "Extracted content exceeds limit ($MAX_TOTAL_EXTRACTED bytes)",
                                R.string.error_zip_total_too_large,
                                arrayOf(MAX_TOTAL_EXTRACTED),
                            )
                        }
                    }
                    e = zis.nextEntry
                }
            }
            return targetDir.name
        }

        /**
         * Validates a local directory and copies the template into [templateDir].
         * Does not depend on Context, for testability.
         * @param overwrite whether to overwrite if the template already exists.
         * @return the imported template id.
         */
        @JvmOverloads
        internal fun copyTemplateFromDirectory(
            sourceDir: File,
            templateDir: File,
            overwrite: Boolean = false,
        ): String {
            Log.d(TAG, "Validating directory: ${sourceDir.absolutePath}")
            val templateJsonFile = File(sourceDir, "template.json")
            if (!templateJsonFile.exists()) {
                throw TemplateLoadException(
                    "template.json not found in directory",
                    R.string.error_template_json_missing,
                )
            }

            val templateJson =
                try {
                    JSONObject(templateJsonFile.readText())
                } catch (_: Exception) {
                    throw TemplateLoadException(
                        "Invalid template.json",
                        R.string.error_template_json_invalid,
                    )
                }

            val templateId = templateJson.optString("id")
            if (templateId.isNullOrBlank()) {
                throw TemplateLoadException(
                    "template.json is missing the id field",
                    R.string.error_template_no_id,
                )
            }
            if (!isValidTemplateId(templateId)) {
                throw TemplateLoadException(
                    "Template id may only contain letters, digits, underscores and hyphens",
                    R.string.error_invalid_template_id,
                )
            }
            if (!templateJson.has("wallpaper")) {
                throw TemplateLoadException(
                    "template.json is missing the wallpaper field",
                    R.string.error_template_no_wallpaper,
                )
            }
            if (!templateJson.has("portraits")) {
                throw TemplateLoadException(
                    "template.json is missing the portraits field",
                    R.string.error_template_no_portraits,
                )
            }

            // Verify referenced files exist and defend against path traversal.
            val wallpaper = templateJson.getString("wallpaper")
            val wallpaperSrc = safeResolveChild(sourceDir, wallpaper)
                ?: throw TemplateLoadException(
                    "Illegal wallpaper path: $wallpaper",
                    R.string.error_illegal_path,
                    arrayOf(wallpaper),
                )
            if (!wallpaperSrc.exists()) {
                throw TemplateLoadException(
                    "Missing wallpaper file: $wallpaper",
                    R.string.error_missing_wallpaper,
                    arrayOf(wallpaper),
                )
            }
            if (wallpaperSrc.length() > MAX_FILE_SIZE) {
                throw TemplateLoadException(
                    "File too large: $wallpaper (max 10MB)",
                    R.string.error_single_file_too_large,
                    arrayOf(wallpaper),
                )
            }

            val portraits = templateJson.getJSONObject("portraits")
            // Validate all portrait files before copying any.
            val portraitFiles = mutableListOf<Pair<String, File>>()
            for (side in listOf("left", "right")) {
                val arr = portraits.optJSONArray(side) ?: continue
                for (i in 0 until arr.length()) {
                    val file = arr.getJSONObject(i).getString("file")
                    val src = safeResolveChild(sourceDir, file)
                        ?: throw TemplateLoadException(
                            "Illegal portrait path: $file",
                            R.string.error_illegal_path,
                            arrayOf(file),
                        )
                    if (!src.exists()) {
                        throw TemplateLoadException(
                            "Missing portrait file: $file",
                            R.string.error_missing_portrait,
                            arrayOf(file),
                        )
                    }
                    val ext = file.substringAfterLast(".", "").lowercase()
                    if (ext !in ALLOWED_EXTENSIONS) {
                        throw TemplateLoadException(
                            "Unsupported file type: $file",
                            R.string.error_unsupported_file_type,
                            arrayOf(file),
                        )
                    }
                    if (src.length() > MAX_FILE_SIZE) {
                        throw TemplateLoadException(
                            "File too large: $file (max 10MB)",
                            R.string.error_single_file_too_large,
                            arrayOf(file),
                        )
                    }
                    portraitFiles.add(file to src)
                }
            }

            val targetDir = resolveTargetDir(templateDir, templateId, overwrite)

            // Copy template.json
            templateJsonFile.copyTo(File(targetDir, "template.json"), overwrite = true)

            // Copy the wallpaper (destination path also needs validation).
            val wallpaperDest = safeResolveChild(targetDir, wallpaper)
                ?: throw TemplateLoadException(
                    "Illegal wallpaper destination path: $wallpaper",
                    R.string.error_illegal_path,
                    arrayOf(wallpaper),
                )
            wallpaperSrc.copyTo(wallpaperDest, overwrite = true)

            // Copy the portraits.
            for ((name, src) in portraitFiles) {
                val dest = safeResolveChild(targetDir, name)
                    ?: throw TemplateLoadException(
                        "Illegal portrait destination path: $name",
                        R.string.error_illegal_path,
                        arrayOf(name),
                    )
                src.copyTo(dest, overwrite = true)
            }

            return templateId
        }

        /** Resolves the target directory; handles existing-template checks and overwrite. */
        private fun resolveTargetDir(templateDir: File, templateId: String, overwrite: Boolean): File {
            val targetDir = File(templateDir, templateId)
            // Defense in depth: templateId already passed the regex check, but confirm the
            // resolved path stays within templateDir.
            val rootCanonical = templateDir.canonicalFile
            val resolved = targetDir.canonicalFile
            if (resolved.path != rootCanonical.path &&
                !resolved.path.startsWith(rootCanonical.path + File.separator)
            ) {
                throw TemplateLoadException(
                    "Template id resolves to an illegal path: $templateId",
                    R.string.error_illegal_path,
                    arrayOf(templateId),
                )
            }
            if (targetDir.exists() && !overwrite) {
                throw TemplateExistsException(
                    templateId,
                    "Template \"$templateId\" already exists, overwrite?",
                    R.string.error_template_exists,
                    arrayOf(templateId),
                )
            }
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()
            return targetDir
        }
    }

    /**
     * Downloads a remote template pack from a URL and imports it.
     * @param url download URL of the ZIP pack.
     * @param allowHttp whether to allow HTTP (unencrypted). Defaults to false; the UI may pass
     *   true after the user confirms the risk.
     * @return the imported template id.
     * @throws TemplateLoadException on various error conditions.
     */
    suspend fun importFromUrl(url: String, allowHttp: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val parsedUrl = validateUrl(url, allowHttp)
            val tempFile = downloadZip(parsedUrl)
            try {
                extractTemplateFromZip(tempFile, File(context.filesDir, TEMPLATE_DIR), allowOverwrite)
            } finally {
                tempFile.delete()
            }
        }

    /**
     * Imports a template from a local ZIP file.
     * @param zipFile the local ZIP file.
     * @return the imported template id.
     * @throws TemplateLoadException on various error conditions.
     */
    suspend fun importFromZip(zipFile: File): String =
        withContext(Dispatchers.IO) {
            if (!zipFile.exists()) {
                throw TemplateLoadException("File not found", R.string.error_file_not_found)
            }
            if (zipFile.length() > MAX_ZIP_SIZE) {
                throw TemplateLoadException("File too large (max 50MB)", R.string.error_file_too_large)
            }
            extractTemplateFromZip(zipFile, File(context.filesDir, TEMPLATE_DIR), allowOverwrite)
        }

    /**
     * Imports a template from a local directory.
     * The directory must contain template.json and every referenced image.
     * @param dir the local template directory.
     * @return the imported template id.
     * @throws TemplateLoadException on various error conditions.
     */
    suspend fun importFromDirectory(dir: File): String =
        withContext(Dispatchers.IO) {
            if (!dir.exists() || !dir.isDirectory) {
                throw TemplateLoadException("Directory not found", R.string.error_dir_not_found)
            }
            copyTemplateFromDirectory(dir, File(context.filesDir, TEMPLATE_DIR), allowOverwrite)
        }

    private fun validateUrl(url: String, allowHttp: Boolean): URL {
        if (url.isBlank()) {
            throw TemplateLoadException("URL cannot be empty", R.string.error_url_empty)
        }
        val parsed =
            try {
                URL(url)
            } catch (_: Exception) {
                throw TemplateLoadException("Invalid URL format", R.string.error_url_invalid)
            }
        val host = parsed.host
        if (host.isNullOrBlank()) {
            throw TemplateLoadException("URL is missing a hostname", R.string.error_url_no_host)
        }
        when (parsed.protocol) {
            "https" -> { /* encrypted, allow */}
            "http" -> {
                // Unencrypted: block by default; the settings UI asks the user to confirm
                // the risk and retries with allowHttp = true.
                if (!allowHttp) {
                    throw HttpProtocolException(
                        "URL uses HTTP (unencrypted); the UI should ask the user to confirm",
                    )
                }
            }
            else -> throw TemplateLoadException(
                "Only HTTP/HTTPS protocols are supported",
                R.string.error_unsupported_protocol,
            )
        }
        return parsed
    }

    private fun downloadZip(url: URL): File {
        val tempFile =
            File.createTempFile("template_download", ".zip", context.cacheDir)
        try {
            val connection = url.openConnection()
            connection.connectTimeout = 10000
            connection.readTimeout = 30000

            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_ZIP_SIZE) {
                throw TemplateLoadException("File too large (max 50MB)", R.string.error_file_too_large)
            }

            var totalRead = 0L
            connection.getInputStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        totalRead += read
                        if (totalRead > MAX_ZIP_SIZE) {
                            throw TemplateLoadException("File too large (max 50MB)", R.string.error_file_too_large)
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }

            if (totalRead == 0L) {
                throw TemplateLoadException("Downloaded file is empty", R.string.error_download_empty)
            }

            return tempFile
        } catch (e: TemplateLoadException) {
            tempFile.delete()
            throw e
        } catch (_: Exception) {
            tempFile.delete()
            throw TemplateLoadException(
                "Download failed, please check network connection",
                R.string.error_download_failed,
            )
        }
    }

    /** Returns the list of imported remote templates. */
    fun getImportedTemplates(): List<TemplateDef> {
        val templateDir =
            File(context.filesDir, TEMPLATE_DIR)
        if (!templateDir.exists()) return emptyList()

        return templateDir
            .listFiles()
            ?.mapNotNull { dir ->
                if (!dir.isDirectory) return@mapNotNull null
                Templates.loadTemplateFromDir(dir)
            }
            ?: emptyList()
    }

    /** Deletes an imported template. */
    fun deleteTemplate(templateId: String): Boolean {
        if (!isValidTemplateId(templateId)) {
            Log.w(TAG, "Refusing to delete invalid template id: $templateId")
            return false
        }
        val templateRoot = File(context.filesDir, TEMPLATE_DIR).canonicalFile
        val dir = File(context.filesDir, "$TEMPLATE_DIR/$templateId")
        val resolved = dir.canonicalFile
        if (resolved.path != templateRoot.path &&
            !resolved.path.startsWith(templateRoot.path + File.separator)
        ) {
            Log.w(TAG, "Refusing to delete path outside template dir: ${dir.path}")
            return false
        }
        return dir.deleteRecursively()
    }
}

/**
 * Base type for all template import/load errors.
 *
 * To support i18n without coupling the loader to Android [android.content.Context],
 * user-facing errors carry a [messageResId] (a `R.string.*`) plus formatting [args].
 * The UI layer resolves them via `context.getString(messageResId, *args)` when set;
 * otherwise it falls back to the English [message] literal (used for logging).
 */
open class TemplateLoadException(
    message: String,
    val messageResId: Int = 0,
    val args: Array<out Any> = emptyArray(),
) : Exception(message)
