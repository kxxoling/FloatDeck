package app.floatdeck.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 远程模板包加载器。
 * 从 URL 下载 ZIP 包，校验后解压到应用私有目录。
 */
class RemoteTemplateLoader(
    private val context: Context,
) {
    companion object {
        private const val TAG = "RemoteTemplateLoader"
        // 模板存储目录
        private const val TEMPLATE_DIR = "remote_templates"

        // ZIP 包最大 50MB
        private const val MAX_ZIP_SIZE = 50L * 1024 * 1024

        // 单个解压文件最大 10MB
        private const val MAX_FILE_SIZE = 10L * 1024 * 1024

        // 允许的图片扩展名
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

        // 允许的文件扩展名（图片 + json）
        private val ALLOWED_EXTENSIONS = IMAGE_EXTENSIONS + "json"
    }

    /**
     * 从 URL 下载并导入远程模板包。
     * @param url ZIP 包的下载地址
     * @return 导入成功的模板 ID
     * @throws TemplateLoadException 各种错误情况
     */
    suspend fun importFromUrl(url: String): String =
        withContext(Dispatchers.IO) {
            val parsedUrl = validateUrl(url)
            val tempFile = downloadZip(parsedUrl)
            try {
                validateAndExtractZip(tempFile)
            } finally {
                tempFile.delete()
            }
        }

    /**
     * 从本地 ZIP 文件导入模板。
     * @param zipFile 本地 ZIP 文件
     * @return 导入成功的模板 ID
     * @throws TemplateLoadException 各种错误情况
     */
    suspend fun importFromZip(zipFile: File): String =
        withContext(Dispatchers.IO) {
            if (!zipFile.exists()) {
                throw TemplateLoadException("文件不存在")
            }
            if (zipFile.length() > MAX_ZIP_SIZE) {
                throw TemplateLoadException("文件过大（最大 50MB）")
            }
            validateAndExtractZip(zipFile)
        }

    /**
     * 从本地目录导入模板。
     * 目录必须包含 template.json 和引用的所有图片文件。
     * @param dir 本地模板目录
     * @return 导入成功的模板 ID
     * @throws TemplateLoadException 各种错误情况
     */
    suspend fun importFromDirectory(dir: File): String =
        withContext(Dispatchers.IO) {
            if (!dir.exists() || !dir.isDirectory) {
                throw TemplateLoadException("目录不存在")
            }
            validateAndCopyDirectory(dir)
        }

    private fun validateUrl(url: String): URL {
        if (url.isBlank()) throw TemplateLoadException("URL 不能为空")
        val parsed =
            try {
                URL(url)
            } catch (_: Exception) {
                throw TemplateLoadException("URL 格式无效")
            }
        if (parsed.protocol !in listOf("http", "https")) {
            throw TemplateLoadException("仅支持 HTTP/HTTPS 协议")
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
                throw TemplateLoadException("文件过大（最大 50MB）")
            }

            var totalRead = 0L
            connection.getInputStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        totalRead += read
                        if (totalRead > MAX_ZIP_SIZE) {
                            throw TemplateLoadException("文件过大（最大 50MB）")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }

            if (totalRead == 0L) {
                throw TemplateLoadException("下载的文件为空")
            }

            return tempFile
        } catch (e: TemplateLoadException) {
            tempFile.delete()
            throw e
        } catch (_: Exception) {
            tempFile.delete()
            throw TemplateLoadException("下载失败，请检查网络连接")
        }
    }

    /** 模板已存在时抛出此异常，UI 层应询问用户是否覆盖 */
    class TemplateExistsException(
        val templateId: String,
        message: String,
    ) : TemplateLoadException(message)

    /** 强制覆盖标志，由 UI 层设置 */
    var allowOverwrite = false

    private fun validateAndExtractZip(zipFile: File): String {
        val templateDir = File(context.filesDir, TEMPLATE_DIR)

        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zipInputStream ->
                var templateJson: JSONObject? = null
                var templateId: String? = null
                var zipFolderName: String? = null
                val entries = mutableListOf<ZipEntry>()

                // 第一遍：扫描所有条目
                var entry: ZipEntry? = zipInputStream.nextEntry
                while (entry != null) {
                    val name = entry.name
                    Log.d(TAG, "Scanning ZIP entry: $name")

                    if (name.contains("..")) {
                        throw TemplateLoadException("ZIP 包含非法路径: $name")
                    }

                    if (entry.isDirectory) {
                        entry = zipInputStream.nextEntry
                        continue
                    }

                    // ZIP 结构要求：文件夹名/文件名（严格一层目录）
                    val slashCount = name.count { it == '/' }
                    if (slashCount == 0) {
                        throw TemplateLoadException(
                            "ZIP 结构错误：文件必须在子文件夹中（如 my_template/template.json）",
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
                            "包含不支持的文件类型：$fileName（仅支持 png/jpg/webp/json）",
                        )
                    }

                    if (entry.size > MAX_FILE_SIZE) {
                        throw TemplateLoadException("单个文件过大：$fileName（最大 10MB）")
                    }

                    if (fileName == "template.json") {
                        zipFolderName = folderName
                        val content =
                            zipInputStream.bufferedReader().readText()
                        templateJson =
                            try {
                                JSONObject(content)
                            } catch (e: Exception) {
                                throw TemplateLoadException("template.json 格式无效: ${e.message}")
                            }

                        templateId = templateJson.optString("id")
                        if (templateId.isNullOrBlank()) {
                            throw TemplateLoadException("template.json 缺少 id 字段")
                        }
                        if (!templateJson.has("wallpaper")) {
                            throw TemplateLoadException("template.json 缺少 wallpaper 字段")
                        }
                        if (!templateJson.has("portraits")) {
                            throw TemplateLoadException("template.json 缺少 portraits 字段")
                        }
                        if (!templateId!!.matches(Regex("[a-zA-Z0-9_-]+"))) {
                            throw TemplateLoadException(
                                "模板 ID 只能包含字母、数字、下划线和连字符",
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
                    throw TemplateLoadException("ZIP 中未找到 template.json")
                }

                // 校验 template.json 引用的图片是否都在 ZIP 中
                val fileNames =
                    entries.map { it.name.substringAfterLast("/") }.toSet()
                Log.d(TAG, "Template files: $fileNames")

                val wallpaper = templateJson.getString("wallpaper")
                if (wallpaper !in fileNames) {
                    throw TemplateLoadException("缺少壁纸文件：$wallpaper")
                }

                val portraits = templateJson.getJSONObject("portraits")
                for (side in listOf("left", "right")) {
                    val arr = portraits.optJSONArray(side) ?: continue
                    for (i in 0 until arr.length()) {
                        val file = arr.getJSONObject(i).getString("file")
                        if (file !in fileNames) {
                            throw TemplateLoadException("缺少立绘文件：$file")
                        }
                    }
                }

                // 检查是否已存在
                val targetDir = File(templateDir, templateId!!)
                if (targetDir.exists() && !allowOverwrite) {
                    throw TemplateExistsException(
                        templateId!!,
                        "模板 \"$templateId\" 已存在，是否覆盖？",
                    )
                }

                // 第二遍：解压到目标目录
                if (targetDir.exists()) targetDir.deleteRecursively()
                targetDir.mkdirs()

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
                        if (fn.isEmpty()) {
                            e = zis.nextEntry
                            continue
                        }

                        val outFile = File(targetDir, fn)
                        if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                            throw TemplateLoadException("ZIP 包含非法路径")
                        }

                        Log.d(TAG, "Extracting: $fn")
                        FileOutputStream(outFile).use { output ->
                            zis.copyTo(output)
                        }
                        e = zis.nextEntry
                    }
                }

                return templateId!!
            }
        } catch (e: TemplateLoadException) {
            Log.e(TAG, "Template validation failed: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ZIP processing failed: ${e.message}", e)
            throw TemplateLoadException("ZIP 处理失败: ${e.message}")
        }
    }

    /**
     * 从本地目录校验并复制模板到应用私有目录。
     */
    private fun validateAndCopyDirectory(sourceDir: File): String {
        Log.d(TAG, "Validating directory: ${sourceDir.absolutePath}")
        val templateJsonFile = File(sourceDir, "template.json")
        if (!templateJsonFile.exists()) {
            throw TemplateLoadException("目录中未找到 template.json")
        }

        val templateJson =
            try {
                JSONObject(templateJsonFile.readText())
            } catch (_: Exception) {
                throw TemplateLoadException("template.json 格式无效")
            }

        val templateId = templateJson.optString("id")
        if (templateId.isNullOrBlank()) {
            throw TemplateLoadException("template.json 缺少 id 字段")
        }
        if (!templateJson.has("wallpaper")) {
            throw TemplateLoadException("template.json 缺少 wallpaper 字段")
        }
        if (!templateJson.has("portraits")) {
            throw TemplateLoadException("template.json 缺少 portraits 字段")
        }
        if (!templateId.matches(Regex("[a-zA-Z0-9_-]+"))) {
            throw TemplateLoadException("模板 ID 只能包含字母、数字、下划线和连字符")
        }

        // 校验引用的文件是否存在
        val wallpaper = templateJson.getString("wallpaper")
        if (!File(sourceDir, wallpaper).exists()) {
            throw TemplateLoadException("缺少壁纸文件：$wallpaper")
        }

        val portraits = templateJson.getJSONObject("portraits")
        for (side in listOf("left", "right")) {
            val arr = portraits.optJSONArray(side) ?: continue
            for (i in 0 until arr.length()) {
                val file = arr.getJSONObject(i).getString("file")
                if (!File(sourceDir, file).exists()) {
                    throw TemplateLoadException("缺少立绘文件：$file")
                }
                val ext = file.substringAfterLast(".", "").lowercase()
                if (ext !in ALLOWED_EXTENSIONS) {
                    throw TemplateLoadException("不支持的文件类型：$file")
                }
                val f = File(sourceDir, file)
                if (f.length() > MAX_FILE_SIZE) {
                    throw TemplateLoadException("单个文件过大：$file（最大 10MB）")
                }
            }
        }

        // 复制到目标目录
        val templateDir = File(context.filesDir, TEMPLATE_DIR)
        val targetDir = File(templateDir, templateId)
        if (targetDir.exists() && !allowOverwrite) {
            throw TemplateExistsException(
                templateId,
                "模板 \"$templateId\" 已存在，是否覆盖？",
            )
        }
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        // 复制 template.json
        templateJsonFile.copyTo(File(targetDir, "template.json"))

        // 复制壁纸
        File(sourceDir, wallpaper).copyTo(File(targetDir, wallpaper))

        // 复制立绘
        for (side in listOf("left", "right")) {
            val arr = portraits.optJSONArray(side) ?: continue
            for (i in 0 until arr.length()) {
                val file = arr.getJSONObject(i).getString("file")
                File(sourceDir, file).copyTo(File(targetDir, file))
            }
        }

        return templateId
    }

    /** 获取已导入的远程模板列表。 */
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

    /** 删除已导入的模板。 */
    fun deleteTemplate(templateId: String): Boolean {
        val dir =
            File(
                context.filesDir,
                "$TEMPLATE_DIR/$templateId",
            )
        return dir.deleteRecursively()
    }
}

open class TemplateLoadException(
    message: String,
) : Exception(message)
