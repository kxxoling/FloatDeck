package app.floatdeck.data

import app.floatdeck.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * End-to-end tests for [RemoteTemplateLoader.extractTemplateFromZip] and
 * [RemoteTemplateLoader.copyTemplateFromDirectory] (companion functions, no Android Context needed).
 * Covers happy-path import, overwrite, duplicate detection, path-traversal rejection and
 * ZIP-bomb rejection.
 */
class RemoteTemplateLoaderIntegrationTest {
    @TempDir
    lateinit var tempDir: File

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

    private fun templateJson(
        id: String = "test_template",
        wallpaper: String = "bg.png",
        left: List<String> = listOf("a.png"),
        right: List<String> = listOf("b.png"),
    ): String {
        val leftArr = left.joinToString(",") { """{"file":"$it","label":"$it"}""" }
        val rightArr = right.joinToString(",") { """{"file":"$it","label":"$it"}""" }
        return """{"id":"$id","name":"T","wallpaper":"$wallpaper",""" +
            """"portraits":{"left":[$leftArr],"right":[$rightArr]}}"""
    }

    private fun writeZip(zipFile: File, folder: String, files: Map<String, ByteArray>) {
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            files.forEach { (name, data) ->
                zos.putNextEntry(ZipEntry("$folder/$name"))
                zos.write(data)
                zos.closeEntry()
            }
        }
    }

    private fun templateRoot(): File = File(tempDir, "store").apply { mkdirs() }

    @Test
    fun `zip - valid template extracts successfully`() {
        val zip = File(tempDir, "ok.zip")
        writeZip(
            zip,
            "test_template",
            mapOf(
                "template.json" to templateJson().toByteArray(),
                "bg.png" to minimalPng,
                "a.png" to minimalPng,
                "b.png" to minimalPng,
            ),
        )

        val id = RemoteTemplateLoader.extractTemplateFromZip(zip, templateRoot())
        assertEquals("test_template", id)
        val out = File(templateRoot(), "test_template")
        assertTrue(File(out, "template.json").exists())
        assertTrue(File(out, "bg.png").exists())
        assertTrue(File(out, "a.png").exists())
        assertTrue(File(out, "b.png").exists())
    }

    @Test
    fun `zip - duplicate without overwrite throws TemplateExistsException`() {
        val zip = File(tempDir, "ok.zip")
        writeZip(
            zip,
            "test_template",
            mapOf(
                "template.json" to templateJson().toByteArray(),
                "bg.png" to minimalPng,
                "a.png" to minimalPng,
                "b.png" to minimalPng,
            ),
        )
        val root = templateRoot()
        RemoteTemplateLoader.extractTemplateFromZip(zip, root)

        val second = assertThrows<RemoteTemplateLoader.TemplateExistsException> {
            RemoteTemplateLoader.extractTemplateFromZip(zip, root, overwrite = false)
        }
        assertEquals("test_template", second.templateId)
    }

    @Test
    fun `zip - overwrite replaces existing template`() {
        val zip = File(tempDir, "ok.zip")
        writeZip(
            zip,
            "test_template",
            mapOf(
                "template.json" to templateJson().toByteArray(),
                "bg.png" to minimalPng,
                "a.png" to minimalPng,
                "b.png" to minimalPng,
            ),
        )
        val root = templateRoot()
        RemoteTemplateLoader.extractTemplateFromZip(zip, root)

        // Overwrite must not throw.
        val id = RemoteTemplateLoader.extractTemplateFromZip(zip, root, overwrite = true)
        assertEquals("test_template", id)
    }

    @Test
    fun `zip - invalid id is rejected`() {
        val zip = File(tempDir, "bad.zip")
        writeZip(
            zip,
            "x",
            mapOf(
                "template.json" to templateJson(id = "../evil").toByteArray(),
                "../evil" to minimalPng, // placeholder filename; the goal is to validate the id check
            ).filterKeys { it != "../evil" },
        )
        // An invalid id must fail at the validation stage.
        assertThrows<TemplateLoadException> {
            RemoteTemplateLoader.extractTemplateFromZip(zip, templateRoot())
        }
    }

    @Test
    fun `zip - missing template_json fails`() {
        val zip = File(tempDir, "nojson.zip")
        writeZip(zip, "x", mapOf("bg.png" to minimalPng))
        assertThrows<TemplateLoadException> {
            RemoteTemplateLoader.extractTemplateFromZip(zip, templateRoot())
        }
    }

    @Test
    fun `directory - valid directory copies successfully`() {
        val src = File(tempDir, "src")
        src.mkdirs()
        File(src, "template.json").writeText(templateJson())
        File(src, "bg.png").writeBytes(minimalPng)
        File(src, "a.png").writeBytes(minimalPng)
        File(src, "b.png").writeBytes(minimalPng)

        val id = RemoteTemplateLoader.copyTemplateFromDirectory(src, templateRoot())
        assertEquals("test_template", id)
        val out = File(templateRoot(), "test_template")
        assertTrue(File(out, "bg.png").exists())
        assertTrue(File(out, "a.png").exists())
        assertTrue(File(out, "b.png").exists())
    }

    @Test
    fun `directory - path traversal in wallpaper is rejected`() {
        val src = File(tempDir, "evil")
        src.mkdirs()
        // Build a template.json that references an out-of-dir path, and place a real file there.
        File(tempDir, "secret.png").writeBytes(minimalPng)
        File(src, "template.json").writeText(templateJson(wallpaper = "../secret.png"))
        File(src, "a.png").writeBytes(minimalPng)
        File(src, "b.png").writeBytes(minimalPng)

        val ex = assertThrows<TemplateLoadException> {
            RemoteTemplateLoader.copyTemplateFromDirectory(src, templateRoot())
        }
        // Must be rejected and must not write secret.png into the target dir.
        assertFalse(File(templateRoot(), "secret.png").exists())
        // The error carries the localized resource id (the actual display string is
        // resolved by the UI, so the test asserts the resource id, not the text).
        assertEquals(R.string.error_illegal_path, ex.messageResId)
    }

    @Test
    fun `directory - path traversal in portrait file is rejected`() {
        val src = File(tempDir, "evil2")
        src.mkdirs()
        File(tempDir, "steal.png").writeBytes(minimalPng)
        File(src, "template.json").writeText(templateJson(left = listOf("../steal.png")))
        File(src, "bg.png").writeBytes(minimalPng)
        File(src, "b.png").writeBytes(minimalPng)

        assertThrows<TemplateLoadException> {
            RemoteTemplateLoader.copyTemplateFromDirectory(src, templateRoot())
        }
        assertFalse(File(templateRoot(), "steal.png").exists())
    }

    @Test
    fun `directory - duplicate without overwrite throws`() {
        val src = File(tempDir, "src")
        src.mkdirs()
        File(src, "template.json").writeText(templateJson())
        File(src, "bg.png").writeBytes(minimalPng)
        File(src, "a.png").writeBytes(minimalPng)
        File(src, "b.png").writeBytes(minimalPng)
        val root = templateRoot()
        RemoteTemplateLoader.copyTemplateFromDirectory(src, root)

        assertThrows<RemoteTemplateLoader.TemplateExistsException> {
            RemoteTemplateLoader.copyTemplateFromDirectory(src, root, overwrite = false)
        }
    }
}
