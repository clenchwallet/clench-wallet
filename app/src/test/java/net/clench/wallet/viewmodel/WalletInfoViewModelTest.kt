package net.clench.wallet.viewmodel

import net.clench.wallet.domain.model.WalletData
import net.clench.wallet.ui.components.MultisigWalletConfigParser
import net.clench.wallet.ui.viewmodel.WalletInfoViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletInfoViewModelTest {

    @Test
    fun `multisig descriptor exposes policy and every keystore`() {
        val descriptor =
            "wsh(sortedmulti(3," +
                "[d0200c4f/48'/0'/0'/2']xpub6Alpha/0/*," +
                "[11223344/48'/0'/0'/2']xpub6Bravo/0/*," +
                "[55667788/48'/0'/0'/2']xpub6Charlie/0/*," +
                "[99aabbcc/48'/0'/0'/2']xpub6Delta/0/*," +
                "[ddeeff00/48'/0'/0'/2']xpub6Echo/0/*" +
                "))#abcd1234"

        val policy = requireNotNull(WalletInfoViewModel.parseMultisigPolicyForDisplay(
            descriptor = descriptor,
            changeDescriptor = descriptor.replace("/0/*", "/1/*")
        ))

        assertNotNull(policy)
        assertEquals("Multi Signature", policy.policyType)
        assertEquals("Native SegWit (P2WSH)", policy.scriptType)
        assertEquals(3, policy.threshold)
        assertEquals(5, policy.totalSigners)
        assertEquals(5, policy.keystores.size)
        assertEquals("D0200C4F", policy.keystores[0].masterFingerprint)
        assertEquals("m/48'/0'/0'/2'", policy.keystores[0].derivationPath)
        assertEquals("xpub6Alpha", policy.keystores[0].xpub)
        assertEquals("11223344", policy.keystores[1].masterFingerprint)
        assertEquals("m/48'/0'/0'/2'", policy.keystores[1].derivationPath)
        assertEquals("xpub6Bravo", policy.keystores[1].xpub)
        assertEquals("55667788", policy.keystores[2].masterFingerprint)
        assertEquals("m/48'/0'/0'/2'", policy.keystores[2].derivationPath)
        assertEquals("xpub6Charlie", policy.keystores[2].xpub)
        assertEquals("99AABBCC", policy.keystores[3].masterFingerprint)
        assertEquals("m/48'/0'/0'/2'", policy.keystores[3].derivationPath)
        assertEquals("xpub6Delta", policy.keystores[3].xpub)
        assertEquals("DDEEFF00", policy.keystores[4].masterFingerprint)
        assertEquals("m/48'/0'/0'/2'", policy.keystores[4].derivationPath)
        assertEquals("xpub6Echo", policy.keystores[4].xpub)
        assertTrue(policy.bsmsDescriptorRecord.startsWith("BSMS 1.0\nwsh(sortedmulti(3,"))
        assertTrue(policy.recoveryChecklist.any { it.contains("PSBT signing drill") })
        assertTrue(policy.keyReplacementWarning.contains("Create a new multisig wallet"))
        assertTrue(policy.keystores[0].checks.contains("Master fingerprint present"))
        assertTrue(policy.keystores[0].warnings.isEmpty())
    }

    @Test
    fun `nested segwit multisig descriptor gets nested script label`() {
        val descriptor =
            "sh(wsh(multi(2," +
                "[aabbccdd/48'/0'/0'/1']xpub6Alpha/0/*," +
                "[11223344/48'/0'/0'/1']xpub6Bravo/0/*" +
                ")))"

        val policy = requireNotNull(WalletInfoViewModel.parseMultisigPolicyForDisplay(descriptor, descriptor))

        assertNotNull(policy)
        assertEquals("Multi Signature", policy.policyType)
        assertEquals("Nested SegWit (P2SH-P2WSH)", policy.scriptType)
        assertEquals(2, policy.threshold)
        assertEquals(2, policy.totalSigners)
    }

    @Test
    fun `invalid multisig threshold returns null policy`() {
        val descriptor =
            "wsh(sortedmulti(3," +
                "[aabbccdd/48'/0'/0'/2']xpub6Alpha/0/*," +
                "[11223344/48'/0'/0'/2']xpub6Bravo/0/*" +
                "))"

        assertNull(WalletInfoViewModel.parseMultisigPolicyForDisplay(descriptor, descriptor))
    }

    @Test
    fun `multisig descriptor with no keystores returns null policy`() {
        val descriptor = "wsh(sortedmulti(1))"

        assertNull(WalletInfoViewModel.parseMultisigPolicyForDisplay(descriptor, descriptor))
    }

    @Test
    fun `BSMS descriptor record round trips through multisig parser`() {
        val descriptor =
            "wsh(sortedmulti(2," +
                "[aabbccdd/48'/0'/0'/2']xpub6Alpha/0/*," +
                "[11223344/48'/0'/0'/2']xpub6Bravo/0/*" +
                "))"
        val policy = requireNotNull(WalletInfoViewModel.parseMultisigPolicyForDisplay(descriptor, descriptor))

        assertEquals(descriptor, MultisigWalletConfigParser.parse(policy.bsmsDescriptorRecord))
    }

    @Test
    fun `keystore without origin exposes health warnings`() {
        val descriptor = "wsh(sortedmulti(1,xpub6Alpha/0/*))"

        val policy = requireNotNull(WalletInfoViewModel.parseMultisigPolicyForDisplay(descriptor, descriptor))

        assertEquals(listOf("Keystore 1: missing master fingerprint", "Keystore 1: missing derivation path"), policy.warnings)
        assertEquals(listOf("Missing master fingerprint", "Missing derivation path"), policy.keystores[0].warnings)
    }

    @Test
    fun `descriptor backup metadata includes multisig recovery data`() {
        val descriptor =
            "wsh(sortedmulti(2," +
                "[aabbccdd/48'/0'/0'/2']xpub6Alpha/0/*," +
                "[11223344/48'/0'/0'/2']xpub6Bravo/0/*" +
                "))"
        val wallet = WalletData(
            id = "wallet-1",
            name = "Vault",
            descriptor = descriptor,
            changeDescriptor = descriptor.replace("/0/*", "/1/*"),
            isWatchOnly = true,
            isMultisig = true
        )

        val metadata = WalletInfoViewModel.buildDescriptorBackupMetadata(wallet)

        assertTrue(metadata.isMultisig)
        assertTrue(metadata.bsmsDescriptorRecord?.contains("BSMS 1.0") == true)
        assertEquals("2 of 2", metadata.multisigPolicy)
        assertTrue(metadata.recoveryChecklist.any { it.contains("descriptor backup") })
        assertTrue(metadata.keyReplacementWarning?.contains("Create a new multisig wallet") == true)
    }
}
