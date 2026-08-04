package net.clench.wallet.security

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureWindowSourcePolicyTest {
    private val sourceRoot = listOf(
        File("app/src/main/java"),
        File("src/main/java"),
    ).first { it.isDirectory }

    @Test
    fun `only ref-counted secure-window utility mutates FLAG_SECURE`() {
        val mutations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val source = file.readText()
                "addFlags(WindowManager.LayoutParams.FLAG_SECURE)" in source ||
                    "clearFlags(WindowManager.LayoutParams.FLAG_SECURE)" in source
            }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
            .toList()

        assertEquals(listOf("net/clench/wallet/ui/util/SecureWindowEffect.kt"), mutations)
    }

    @Test
    fun `hardware setup routes cannot opt out while collecting card credentials`() {
        val importSource = File(
            sourceRoot,
            "net/clench/wallet/ui/screens/ImportWalletScreen.kt"
        ).readText()
        val multisigSource = File(
            sourceRoot,
            "net/clench/wallet/ui/screens/CreateMultisigScreen.kt"
        ).readText()

        assertTrue("SecureWindowEffect()" in importSource)
        assertFalse("SecureWindowEffect(enabled = !hardwareWalletMode)" in importSource)
        assertTrue("SecureWindowEffect()" in multisigSource)
    }
}
