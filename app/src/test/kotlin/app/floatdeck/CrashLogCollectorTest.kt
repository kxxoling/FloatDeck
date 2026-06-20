package app.floatdeck

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Covers [CrashLogCollector.logsToTrim] capacity-trimming logic.
 * The original `trimOldLogs` captured `files.size` once outside the loop and never
 * updated it, so it wiped ALL logs at once; these tests ensure only the oldest
 * surplus files are removed now.
 */
class CrashLogCollectorTest {
    private fun file(name: String, modified: Long): File {
        // Only used to carry a lastModified value; no real file is created.
        return File("/tmp/floatdeck-test/$name").apply {
            setLastModified(modified)
        }
    }

    @Test
    fun `logsToTrim returns nothing when under capacity`() {
        val files = arrayOf(file("a", 1L), file("b", 2L))
        assertTrue(CrashLogCollector.logsToTrim(files, maxKeep = 9).isEmpty())
    }

    @Test
    fun `logsToTrim keeps newest and removes only the surplus`() {
        val files =
            arrayOf(
                file("oldest", 1L),
                file("mid", 5L),
                file("newest", 9L),
            )
        // maxKeep=1 => keep only newest, remove the other two.
        val toRemove = CrashLogCollector.logsToTrim(files, maxKeep = 1)
        assertEquals(2, toRemove.size)
        assertEquals("oldest", toRemove[0].name)
        assertEquals("mid", toRemove[1].name)
    }

    @Test
    fun `logsToTrim handles exact capacity`() {
        val files = arrayOf(file("a", 1L), file("b", 2L))
        // Count equals maxKeep, nothing to remove.
        assertTrue(CrashLogCollector.logsToTrim(files, maxKeep = 2).isEmpty())
    }

    @Test
    fun `logsToTrim on empty array is empty`() {
        assertTrue(CrashLogCollector.logsToTrim(emptyArray(), maxKeep = 5).isEmpty())
    }
}
