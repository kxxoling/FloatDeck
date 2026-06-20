package app.floatdeck.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Real unit tests for [RemoteTemplateLoader]'s security-critical logic.
 * Calls internal companion functions directly (same-module visible, no Android Context),
 * covering path traversal, ZIP bombs and id validation.
 */
class RemoteTemplateLoaderSecurityTest {
    // ---------- safeResolveChild : path traversal defense ----------

    @Test
    fun `safeResolveChild accepts plain filename`() {
        val base = File("/data/templates/tpl")
        val resolved = RemoteTemplateLoader.safeResolveChild(base, "bg.png")
        assertNotNull(resolved)
        assertEquals(File("/data/templates/tpl/bg.png"), resolved)
    }

    @Test
    fun `safeResolveChild rejects parent traversal`() {
        val base = File("/data/templates/tpl")
        assertNull(RemoteTemplateLoader.safeResolveChild(base, "../../etc/passwd"))
        assertNull(RemoteTemplateLoader.safeResolveChild(base, "../sibling/secret"))
    }

    @Test
    fun `safeResolveChild rejects absolute paths`() {
        val base = File("/data/templates/tpl")
        assertNull(RemoteTemplateLoader.safeResolveChild(base, "/etc/passwd"))
        assertNull(RemoteTemplateLoader.safeResolveChild(base, "\\etc\\passwd"))
    }

    @Test
    fun `safeResolveChild rejects windows drive letters`() {
        val base = File("/data/templates/tpl")
        assertNull(RemoteTemplateLoader.safeResolveChild(base, "C:evil"))
    }

    @Test
    fun `safeResolveChild rejects sibling with same prefix`() {
        val base = File("/data/templates/tpl")
        // tpl-evil must not be treated as a child of tpl
        assertNull(RemoteTemplateLoader.safeResolveChild(base, "../tpl-evil/secret"))
    }

    @Test
    fun `safeResolveChild rejects blank`() {
        val base = File("/data/templates/tpl")
        assertNull(RemoteTemplateLoader.safeResolveChild(base, ""))
        assertNull(RemoteTemplateLoader.safeResolveChild(base, "   "))
    }

    // ---------- isValidTemplateId ----------

    @Test
    fun `isValidTemplateId accepts safe ids`() {
        assertTrue(RemoteTemplateLoader.isValidTemplateId("a"))
        assertTrue(RemoteTemplateLoader.isValidTemplateId("my_template-1"))
        assertTrue(RemoteTemplateLoader.isValidTemplateId("ABC_123-x"))
    }

    @Test
    fun `isValidTemplateId rejects traversal and special chars`() {
        assertFalse(RemoteTemplateLoader.isValidTemplateId(""))
        assertFalse(RemoteTemplateLoader.isValidTemplateId("../etc"))
        assertFalse(RemoteTemplateLoader.isValidTemplateId("a/b"))
        assertFalse(RemoteTemplateLoader.isValidTemplateId("a id"))
        assertFalse(RemoteTemplateLoader.isValidTemplateId("a:id"))
    }

    // ---------- copyToWithLimit : ZIP bomb defense ----------

    @Test
    fun `copyToWithLimit copies within limit`() {
        val input = ByteArrayInputStream(ByteArray(100))
        val out = ByteArrayOutputStream()
        val written =
            with(RemoteTemplateLoader) {
                input.copyToWithLimit(out, 200)
            }
        assertEquals(100L, written)
        assertEquals(100, out.size())
    }

    @Test
    fun `copyToWithLimit throws when exceeding limit`() {
        val input = ByteArrayInputStream(ByteArray(1000))
        val out = ByteArrayOutputStream()
        assertThrows<java.io.IOException> {
            with(RemoteTemplateLoader) {
                input.copyToWithLimit(out, 100)
            }
        }
    }

    @Test
    fun `copyToWithLimit limit of zero rejects any byte`() {
        val input = ByteArrayInputStream(byteArrayOf(1))
        val out = ByteArrayOutputStream()
        assertThrows<java.io.IOException> {
            with(RemoteTemplateLoader) {
                input.copyToWithLimit(out, 0)
            }
        }
    }
}
