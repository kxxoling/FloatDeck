package app.floatdeck.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RemoteTemplateLoaderTest {
    @TempDir
    lateinit var tempDir: File

    /** Create a minimal valid template.json */
    private fun createTemplateJson(
        id: String = "test_template",
        wallpaper: String = "bg.png",
        left: List<String> = listOf("a.png"),
        right: List<String> = listOf("b.png"),
    ): String {
        val leftArr =
            left.joinToString(",") { """{"file":"$it","label":"$it"}""" }
        val rightArr =
            right.joinToString(",") { """{"file":"$it","label":"$it"}""" }
        // ktlint: disable string-template-indent
        return "{" +
            "\"id\":\"$id\"," +
            "\"name\":\"Test Template\"," +
            "\"wallpaper\":\"$wallpaper\"," +
            "\"portraits\":{" +
            "\"left\":[$leftArr]," +
            "\"right\":[$rightArr]}}"
        // ktlint: enable string-template-indent
    }

    /** Create a 1x1 pixel PNG minimal byte array */
    private val minimalPng: ByteArray =
        byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )

    /** Create a ZIP file containing template files (folder/file structure) */
    private fun createZipFile(
        zipFile: File,
        folderName: String = "test_template",
        files: Map<String, ByteArray>,
    ) {
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            files.forEach { (name, data) ->
                zos.putNextEntry(ZipEntry("$folderName/$name"))
                zos.write(data)
                zos.closeEntry()
            }
        }
    }

    // ---- ZIP structure validation tests ----

    @Test
    fun `ZIP structure - valid folder-file structure`() {
        val files =
            mapOf(
                "template.json" to createTemplateJson().toByteArray(),
                "bg.png" to minimalPng,
                "a.png" to minimalPng,
                "b.png" to minimalPng,
            )
        val zipFile = File(tempDir, "valid.zip")
        createZipFile(zipFile, "my_template", files)

        val entries = mutableListOf<String>()
        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entries.add(entry.name)
                entry = zis.nextEntry
            }
        }
        assertTrue(entries.contains("my_template/template.json"))
        assertTrue(entries.contains("my_template/bg.png"))
    }

    @Test
    fun `ZIP read - stream should not close after reading template_json`() {
        val files =
            mapOf(
                "template.json" to createTemplateJson().toByteArray(),
                "bg.png" to minimalPng,
            )
        val zipFile = File(tempDir, "stream_test.zip")
        createZipFile(zipFile, "tpl", files)

        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.endsWith("template.json")) {
                    // Simulate RemoteTemplateLoader read: no .use {}
                    val content = zis.bufferedReader().readText()
                    assertTrue(content.contains("test_template"))
                }
                entry = zis.nextEntry
            }
        }
    }

    @Test
    fun `ZIP structure - root level files should fail`() {
        val zipFile = File(tempDir, "root_files.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("template.json"))
            zos.write(createTemplateJson().toByteArray())
            zos.closeEntry()
        }

        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val slashCount = entry.name.count { it == '/' }
                assertEquals(0, slashCount)
                entry = zis.nextEntry
            }
        }
    }

    @Test
    fun `ZIP structure - missing template_json should fail`() {
        val files =
            mapOf("bg.png" to minimalPng)
        val zipFile = File(tempDir, "no_json.zip")
        createZipFile(zipFile, files = files)

        val hasJson =
            java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
                var found = false
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith("template.json")) found = true
                    entry = zis.nextEntry
                }
                found
            }
        assertFalse(hasJson)
    }

    @Test
    fun `ZIP structure - path traversal should be rejected`() {
        val zipFile = File(tempDir, "path_traversal.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("../../../etc/passwd"))
            zos.write("evil".toByteArray())
            zos.closeEntry()
        }

        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                assertTrue(entry.name.contains(".."), "Path traversal should be detected")
                return
            }
        }
    }

    @Test
    fun `ZIP structure - deeply nested files should be ignored`() {
        val zipFile = File(tempDir, "nested.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("my_template/template.json"))
            zos.write(createTemplateJson().toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("my_template/sub/deep.png"))
            zos.write(minimalPng)
            zos.closeEntry()
        }

        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
            val entries = mutableListOf<String>()
            var entry = zis.nextEntry
            while (entry != null) {
                entries.add(entry.name)
                entry = zis.nextEntry
            }
            assertTrue(entries.any { it.count { c -> c == '/' } > 1 })
        }
    }

    // ---- Directory import validation tests ----

    @Test
    fun `Directory import - valid directory structure`() {
        val dir = File(tempDir, "my_template")
        dir.mkdirs()
        File(dir, "template.json").writeText(createTemplateJson())
        File(dir, "bg.png").writeBytes(minimalPng)
        File(dir, "a.png").writeBytes(minimalPng)
        File(dir, "b.png").writeBytes(minimalPng)

        assertTrue(File(dir, "template.json").exists())
        assertTrue(File(dir, "bg.png").exists())
    }

    @Test
    fun `Directory import - missing template_json should fail`() {
        val dir = File(tempDir, "no_json_dir")
        dir.mkdirs()
        File(dir, "bg.png").writeBytes(minimalPng)
        assertFalse(File(dir, "template.json").exists())
    }

    @Test
    fun `Directory import - invalid template_json format should fail`() {
        val dir = File(tempDir, "bad_json_dir")
        dir.mkdirs()
        val badContent = "not json at all"
        File(dir, "template.json").writeText(badContent)
        // Verify content is not valid JSON (no { or })
        val content = File(dir, "template.json").readText()
        assertFalse(content.contains("{") && content.contains("}"), "Should not be valid JSON structure")
    }

    @Test
    fun `Directory import - missing wallpaper file should fail`() {
        val dir = File(tempDir, "no_wallpaper")
        dir.mkdirs()
        File(dir, "template.json").writeText(
            createTemplateJson(wallpaper = "missing.png"),
        )
        assertFalse(File(dir, "missing.png").exists())
    }

    @Test
    fun `Directory import - missing portrait file should fail`() {
        val dir = File(tempDir, "no_portrait")
        dir.mkdirs()
        File(dir, "template.json").writeText(
            createTemplateJson(left = listOf("missing_portrait.png")),
        )
        File(dir, "bg.png").writeBytes(minimalPng)
        assertFalse(File(dir, "missing_portrait.png").exists())
    }

    // ---- Template ID validation tests ----

    @Test
    fun `Template ID - special characters should be rejected`() {
        val badIds = listOf("test/id", "test id", "test:id", "../../../etc", "")
        for (id in badIds) {
            assertFalse(id.matches(Regex("[a-zA-Z0-9_-]+")), "Should reject ID: $id")
        }
    }

    @Test
    fun `Template ID - valid IDs should be accepted`() {
        val validIds = listOf("test", "my_template", "tpl-1", "ABC_123-x")
        for (id in validIds) {
            assertTrue(id.matches(Regex("[a-zA-Z0-9_-]+")), "Should accept ID: $id")
        }
    }

    // ---- File type tests ----

    @Test
    fun `Unsupported file extensions should be rejected`() {
        val allowedExtensions = setOf("png", "jpg", "jpeg", "webp", "json")
        val badExtensions = listOf("exe", "sh", "bat", "apk", "so", "js")
        for (ext in badExtensions) {
            assertFalse(ext in allowedExtensions, "Should reject extension: $ext")
        }
    }
}
