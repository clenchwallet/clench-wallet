package net.clench.wallet.ui.lifehash

import org.junit.Assert.assertEquals
import org.junit.Test

class LifeHashTest {
    @Test
    fun `version 2 output matches Toucan golden vector`() {
        val image = LifeHash.makeFromUTF8("Hello", LifeHashVersion.VERSION2, 1, false)

        assertEquals(32, image.width())
        assertEquals(32, image.height())

        val expected = byteArrayOf(
            -110, 126, -126, -78, 104, 92, -74, 101, 87, -54,
            88, 64, -57, 89, 66, -59, 90, 69, -74, 101,
            87, -76, 102, 89, -97, 117, 114, -46, 82, 54
        )
        expected.forEachIndexed { index, byte ->
            assertEquals(byte, image.colors()[index].toByte())
        }
    }
}
