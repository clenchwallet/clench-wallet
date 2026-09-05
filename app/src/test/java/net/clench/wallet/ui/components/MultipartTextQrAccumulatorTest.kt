package net.clench.wallet.ui.components

import org.junit.Assert.*
import org.junit.Test

class MultipartTextQrAccumulatorTest {
    @Test fun `reversed and repeated frames assemble in order`() {
        val a = MultipartTextQrAccumulator(4, 12)
        assertNull(a.receive(2, 3, "b"))
        assertNull(a.receive(2, 3, "b"))
        assertNull(a.receive(3, 3, "c"))
        assertEquals("abc", a.receive(1, 3, "a"))
    }
    @Test fun `conflicting duplicate resets and cannot complete old payload`() {
        val a = MultipartTextQrAccumulator(4, 12)
        a.receive(1, 2, "a")
        assertThrows(IllegalArgumentException::class.java) { a.receive(1, 2, "x") }
        assertEquals(0, a.collectedFrames)
        assertNull(a.receive(2, 2, "y"))
        assertEquals("xy", a.receive(1, 2, "x"))
    }
    @Test fun `changed total and bounds fail without retaining state`() {
        for (bad in listOf(Triple(1, 3, "b"), Triple(0, 2, "b"), Triple(2, 5, "b"), Triple(2, 2, "123456"))) {
            val a = MultipartTextQrAccumulator(4, 6)
            a.receive(1, 2, "a")
            assertThrows(IllegalArgumentException::class.java) { a.receive(bad.first, bad.second, bad.third) }
            assertEquals(0, a.totalFrames)
            assertEquals(0, a.collectedFrames)
        }
    }
    @Test fun `exact byte budget accepts repeated frame without double counting`() {
        val a = MultipartTextQrAccumulator(2, 4)
        a.receive(1, 2, "ab")
        a.receive(1, 2, "ab")
        assertEquals("abcd", a.receive(2, 2, "cd"))
    }
}
