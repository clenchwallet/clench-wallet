package net.clench.wallet.ui.components

import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapsignerPinInputTest {
    @Test
    fun `current PINs use numeric keypad and legacy mode keeps full keyboard`() {
        assertEquals(KeyboardType.NumberPassword, tapsignerPinKeyboardType(useLegacyKeyboard = false))
        assertEquals(KeyboardType.Password, tapsignerPinKeyboardType(useLegacyKeyboard = true))
    }

    @Test
    fun `PIN policy accepts numeric and legacy printable ASCII boundaries`() {
        assertTrue(isValidTapsignerPin("123456"))
        assertTrue(isValidTapsignerPin("1".repeat(TAPSIGNER_PIN_MAX_LENGTH)))
        assertTrue(isValidTapsignerPin("old-PIN!"))
        assertTrue(isValidTapsignerPin("!!!!!!"))
        assertTrue(isValidTapsignerPin("~~~~~~"))
    }

    @Test
    fun `PIN policy rejects invalid lengths spaces and non ASCII`() {
        assertFalse(isValidTapsignerPin("12345"))
        assertFalse(isValidTapsignerPin("1".repeat(TAPSIGNER_PIN_MAX_LENGTH + 1)))
        assertFalse(isValidTapsignerPin("123 456"))
        assertFalse(isValidTapsignerPin("12345\n"))
        assertFalse(isValidTapsignerPin("12345é"))
    }

    @Test
    fun `PIN policy validates secret char arrays without converting them to strings`() {
        val valid = charArrayOf('1', '2', '3', '4', '5', '6')
        val invalid = charArrayOf('1', '2', '3', '4', '5', ' ')

        assertTrue(isValidTapsignerPin(valid))
        assertFalse(isValidTapsignerPin(invalid))
    }
}
