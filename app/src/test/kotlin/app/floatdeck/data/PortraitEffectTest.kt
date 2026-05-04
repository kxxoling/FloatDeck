package app.floatdeck.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PortraitEffectTest {
    @Test
    fun `fromKey - known keys return correct enum`() {
        assertEquals(PortraitEffect.NONE, PortraitEffect.fromKey("none"))
        assertEquals(PortraitEffect.ICE, PortraitEffect.fromKey("ice"))
        assertEquals(PortraitEffect.HOLO, PortraitEffect.fromKey("holo"))
    }

    @Test
    fun `fromKey - unknown key returns NONE`() {
        assertEquals(PortraitEffect.NONE, PortraitEffect.fromKey("unknown"))
        assertEquals(PortraitEffect.NONE, PortraitEffect.fromKey(""))
    }

    @Test
    fun `enum entries match expected keys`() {
        val keys = PortraitEffect.entries.map { it.key }
        assertEquals(listOf("none", "ice", "holo"), keys)
    }
}
