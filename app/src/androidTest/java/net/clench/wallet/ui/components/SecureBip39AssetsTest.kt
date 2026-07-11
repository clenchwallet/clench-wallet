package net.clench.wallet.ui.components

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureBip39AssetsTest {
    @Test
    fun bundledWordListSupportsNoImePickerOnDevice() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val words = context.assets.open("bip39_english.txt").bufferedReader().use { it.readLines() }

        assertEquals(2048, words.size)
        assertEquals(2048, words.toSet().size)
        assertEquals("abandon", words.first())
        assertEquals("zoo", words.last())
        assertTrue(Bip39WordPicker.isComplete(List(12) { words.first() }, 12))
    }
}
