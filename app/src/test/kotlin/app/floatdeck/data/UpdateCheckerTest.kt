package app.floatdeck.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class UpdateCheckerTest {

    @ParameterizedTest
    @CsvSource(
        "1.0.0, 0.9.0, true",
        "1.0.0, 1.0.0, false",
        "0.9.0, 1.0.0, false",
        "1.1.0, 1.0.0, true",
        "1.0.1, 1.0.0, true",
        "2.0.0, 1.9.9, true",
        "v1.0.0, v0.9.0, true",
        "v1.0.0, 1.0.0, false",
        "1.0.0, v1.0.0, false",
        "1.0.0, 0.9.9, true",
        "1.0.0-beta, 0.9.0, true",
        "1.0.0-beta, 1.0.0, false",
        "1.0.0, 1.0.0-beta, true",
        "1.0.0-alpha, 1.0.0-beta, false",
        "1.0.0, 1.0.0.0, false",
        "1.0, 0.9, true",
        "1, 0, true",
        "1, 1, false",
        "1.x.0, 1.0.0, false",
        "1.0.0, 1.x.0, false",
    )
    fun `isNewer compares versions correctly`(remote: String, current: String, expected: Boolean) {
        assertEquals(expected, UpdateChecker.isNewer(remote, current))
    }

    @Test
    fun `isNewer - pre-release suffix should not make version newer than stable`() {
        assertFalse(UpdateChecker.isNewer("1.0.0-beta", "1.0.0"))
    }

    @Test
    fun `isNewer - pre-release with same base version should be considered older or equal`() {
        assertFalse(UpdateChecker.isNewer("1.0.0-alpha", "1.0.0-beta"))
    }

    @Test
    fun `isNewer - invalid version should not be considered newer`() {
        assertFalse(UpdateChecker.isNewer("1.x.0", "1.0.0"))
    }

    @Test
    fun `isNewer - invalid current version should not trigger update`() {
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.x.0"))
    }
}
