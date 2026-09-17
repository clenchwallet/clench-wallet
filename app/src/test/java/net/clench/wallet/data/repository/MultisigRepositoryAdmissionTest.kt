package net.clench.wallet.data.repository

import android.content.Context
import android.util.Log
import io.mockk.*
import java.lang.reflect.InvocationTargetException
import org.bitcoindevkit.Network
import org.junit.Assert.*
import org.junit.Test

/** Existing repository admission API: also runs unchanged against the pre-fix source. */
class MultisigRepositoryAdmissionTest {
    @Test fun `two account fields cannot pass through repository normalization as three signers`() {
        mockkStatic(Log::class)
        every { Log.isLoggable(any(), any()) } returns false
        try {
            val repository = BdkBitcoinRepository(mockk<Context>(), mockk(), mockk(), mockk(), mockk(),
                mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk())
            val admission = repository.javaClass.getDeclaredMethod("normalizeMultisigSignerKey", String::class.java,
                Int::class.javaPrimitiveType, Network::class.java).apply { isAccessible = true }
            val key = "[01020304/48'/0'/0'/2']xpub6DYLKsxfR6wLthZeqQB6KeTfqqmyNkPZqmjTuJ4jMNeoBqfwvFax4VVTALMgWXegeDnU1JmnCL7sDYpAtVwhpDXXVcZugxxcXdu7ipEbCHV"
            assertEquals(key, admission.invoke(repository, key, 1, Network.BITCOIN))
            val rejected = assertThrows(InvocationTargetException::class.java) {
                admission.invoke(repository, "$key/0/*,$key/0/*", 1, Network.BITCOIN)
            }
            assertTrue(rejected.cause is IllegalArgumentException)
        } finally { unmockkAll() }
    }
}
