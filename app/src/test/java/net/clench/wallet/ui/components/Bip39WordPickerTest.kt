package net.clench.wallet.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Bip39WordPickerTest {
    private val words = File("src/main/assets/bip39_english.txt").readLines().map(String::trim)

    @Test
    fun `bundled English list is canonical size and ordered`() {
        assertEquals(2048, words.size)
        assertEquals("abandon", words.first())
        assertEquals("zoo", words.last())
        assertEquals(words.sorted(), words)
        assertEquals(words.size, words.toSet().size)
    }

    @Test
    fun `suggestions are prefix bounded and deterministic`() {
        assertEquals(
            listOf("abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract"),
            Bip39WordPicker.suggestions("AB", words)
        )
        assertTrue(Bip39WordPicker.suggestions("notaword", words).isEmpty())
    }

    @Test
    fun `normalization accepts only canonical words and enforces limit`() {
        assertEquals(
            listOf("abandon", "ability"),
            Bip39WordPicker.normalize(listOf(" Abandon ", "invalid", "ABILITY", "able"), words.toSet(), 2)
        )
    }

    @Test
    fun `completion only accepts supported exact counts`() {
        assertTrue(Bip39WordPicker.isComplete(List(12) { "abandon" }, 12))
        assertTrue(Bip39WordPicker.isComplete(List(24) { "abandon" }, 24))
        assertFalse(Bip39WordPicker.isComplete(List(11) { "abandon" }, 12))
        assertFalse(Bip39WordPicker.isComplete(List(15) { "abandon" }, 15))
    }
}
