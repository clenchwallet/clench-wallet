package net.clench.wallet.security

import java.io.ByteArrayInputStream
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedInputTest {
    @Test
    fun `bounded text accepts input at limit`() {
        assertEquals("1234", StringReader("1234").readTextBounded(4))
    }

    @Test
    fun `bounded text rejects input over limit`() {
        val failure = runCatching { StringReader("12345").readTextBounded(4) }.exceptionOrNull()
        assertTrue(failure?.message?.contains("safety limit") == true)
    }

    @Test
    fun `bounded bytes reject input over limit`() {
        val failure = runCatching {
            ByteArrayInputStream(ByteArray(5)).readBytesBounded(4)
        }.exceptionOrNull()
        assertTrue(failure?.message?.contains("safety limit") == true)
    }
}
