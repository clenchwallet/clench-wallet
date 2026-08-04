package net.clench.wallet.ui.picker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerSourcePolicyTest {
    @Test
    fun `document pickers are owned only by MainActivity`() {
        val sourceRoot = File("src/main/java")
        val forbiddenContracts = listOf(
            "ActivityResultContracts.OpenDocument",
            "ActivityResultContracts.CreateDocument",
            "ActivityResultContracts.GetContent",
            "Intent.ACTION_OPEN_DOCUMENT",
            "Intent.ACTION_CREATE_DOCUMENT"
        )
        val owners = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val source = file.readText()
                forbiddenContracts.any(source::contains)
            }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
            .toList()

        assertEquals(listOf("net/clench/wallet/ui/MainActivity.kt"), owners)
    }

    @Test
    fun `picker broker cannot persist grants or protected payload fields`() {
        val brokerSource = File(
            "src/main/java/net/clench/wallet/ui/picker/PickerRoundTripBroker.kt"
        ).readText()
        val mainActivitySource = File(
            "src/main/java/net/clench/wallet/ui/MainActivity.kt"
        ).readText()

        assertFalse("takePersistableUriPermission" in brokerSource)
        assertFalse("takePersistableUriPermission" in mainActivitySource)
        assertFalse("FLAG_GRANT_PERSISTABLE_URI_PERMISSION" in mainActivitySource)
        for (forbiddenField in listOf(
            "mnemonic:", "seed:", "pin:", "cvc:", "passphrase:", "wif:",
            "psbt:", "transaction:", "descriptor:", "xpub:", "backupData:"
        )) {
            assertFalse("Picker broker exposes protected field $forbiddenField", forbiddenField in brokerSource)
        }
        assertTrue("@Singleton" in brokerSource)
        assertTrue("@Inject constructor()" in brokerSource)
    }

    @Test
    fun `PSBT picker routes send system back through scoped handoff cleanup`() {
        for (screen in listOf("HardwareWalletPsbtScreen.kt", "PhoneSignerPsbtScreen.kt")) {
            val source = File(
                "src/main/java/net/clench/wallet/ui/screens/$screen"
            ).readText()
            assertTrue("$screen must install a BackHandler", "BackHandler(" in source)
            assertTrue(
                "$screen must route predictive back through secureBack",
                "onBack = secureBack" in source
            )
        }
    }
}
