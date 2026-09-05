package net.clench.wallet.data.network

import java.io.IOException
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class BoundedLineReaderTest {
    @Test fun `accepts bounded CRLF LF and EOF responses`() {
        val r = BoundedLineReader(StringReader("ab\r\ncd\ne"), 3, 9)
        assertEquals("ab", r.readLine()); assertEquals("cd", r.readLine())
        assertEquals("e", r.readLine()); assertNull(r.readLine())
    }
    @Test fun `oversized unterminated line stops before unbounded allocation`() {
        val r = BoundedLineReader(StringReader("a".repeat(100)), 16, 100)
        assertThrows(IOException::class.java) { r.readLine() }
    }
    @Test fun `total limit applies across individually valid lines`() {
        val r = BoundedLineReader(StringReader("ab\ncd\nef\n"), 2, 6)
        assertEquals("ab", r.readLine()); assertEquals("cd", r.readLine())
        assertThrows(IOException::class.java) { r.readLine() }
    }
}
