package net.clench.wallet.viewmodel

import net.clench.wallet.domain.model.WalletData
import net.clench.wallet.domain.model.SignerAccountKeyParser
import net.clench.wallet.data.local.entity.WalletKeystoreMetadataEntity
import net.clench.wallet.ui.viewmodel.WalletInfoViewModel.Companion.withMetadata
import net.clench.wallet.ui.components.MultisigWalletConfigParser
import net.clench.wallet.ui.util.DescriptorDisplayPolicy
import net.clench.wallet.ui.viewmodel.WalletInfoViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletInfoViewModelTest {

    private fun metadataPolicy(): WalletInfoViewModel.MultisigPolicyInfo {
        val descriptor = "wsh(sortedmulti(2,[01020304/48'/0'/0'/2']$originalKey/0/*,[05060708/48'/0'/0'/2']$distinctKey/0/*))"
        return requireNotNull(WalletInfoViewModel.parseMultisigPolicyForDisplay(
            descriptor, descriptor.replace("/0/*", "/1/*")
        ))
    }

    private fun createdMetadata(policy: WalletInfoViewModel.MultisigPolicyInfo) =
        policy.keystores.mapIndexed { i, key ->
            WalletKeystoreMetadataEntity("wallet", SignerAccountKeyParser.stableId(
                key.masterFingerprint, key.derivationPath, key.xpub
            ), "Saved signer ${i + 1}", updatedAtEpochMs = 10)
        }.associateBy { it.keyId }

    @Test fun `newly created signer labels match descriptor without false warning`() {
        val policy = metadataPolicy()
        val displayed = policy.withMetadata(createdMetadata(policy))
        assertEquals(listOf("Saved signer 1", "Saved signer 2"), displayed.keystores.map { it.label })
        assertTrue(displayed.warnings.isEmpty())
        assertEquals(policy.descriptor, displayed.descriptor)
        assertEquals(policy.keystores.map { it.keyId }, displayed.keystores.map { it.keyId })
    }

    @Test fun `historical renamed labels retain their stored ids`() {
        val policy = metadataPolicy()
        val metadata = policy.keystores.map { key ->
            WalletKeystoreMetadataEntity("wallet", key.keyId, "Renamed ${key.masterFingerprint}", updatedAtEpochMs = 20)
        }.associateBy { it.keyId }
        val displayed = policy.withMetadata(metadata)
        assertEquals(metadata.values.map { it.label }, displayed.keystores.map { it.label })
        assertTrue(displayed.warnings.isEmpty())
    }

    @Test fun `newest label wins when creation and rename rows coexist`() {
        val policy = metadataPolicy()
        val created = createdMetadata(policy)
        val renamed = WalletKeystoreMetadataEntity("wallet", policy.keystores[0].keyId,
            "Later rename", updatedAtEpochMs = 20)
        val both = created + (renamed.keyId to renamed)
        val displayed = policy.withMetadata(both)
        assertEquals("Later rename", displayed.keystores[0].label)
        assertTrue(displayed.warnings.isEmpty())
        val updatedCreation = created.values.first().copy(label = "Later import", updatedAtEpochMs = 30)
        assertEquals("Later import", policy.withMetadata(both + (updatedCreation.keyId to updatedCreation)).keystores[0].label)
    }

    @Test fun `unmatched and missing signer metadata still warns`() {
        val policy = metadataPolicy()
        val created = createdMetadata(policy)
        val unrelated = WalletKeystoreMetadataEntity("wallet", "unrelated", "Do not apply", updatedAtEpochMs = 99)
        val displayed = policy.withMetadata(created + (unrelated.keyId to unrelated))
        assertTrue(displayed.warnings.any { it.contains("actual signer set") })
        assertEquals(listOf("Saved signer 1", "Saved signer 2"), displayed.keystores.map { it.label })
        assertTrue(policy.withMetadata(created - created.keys.first()).warnings.any { it.contains("actual signer set") })
    }

    // Synthetic public-only extended keys: generator point and repeated chain bytes.
    // The alias changes version/depth/parent/child metadata, not key or chain code.
    private val originalKey = "xpub6DYLKsxfR6wLthZeqQB6KeTfqqmyNkPZqmjTuJ4jMNeoBqfwvFax4VVTALMgWXegeDnU1JmnCL7sDYpAtVwhpDXXVcZugxxcXdu7ipEbCHV"
    private val aliasedKey = "zpub6u7i5vktX9WUnSp2i81QnF9vAU2KgmEKdYNX7zgr5aii6LynJqhEHuakvoTXdtC6ZSjQueD9Y3vJe6KqpNmTzdj4DRES9hDsNTXy8DujuY2"
    private val distinctKey = "xpub6DYLKsxfR6wLti9GzcuLu5q4onJoiUfuZqVeZr2e1JsBnmpe9ybre1hSXHn52jtvgqGGfJE2k8sw5naQNwMU3auuueZzJDhm579cNQT3e9o"

    @Test
    fun `legacy aliased policy remains displayable and warns without changing its descriptor`() {
        val descriptor = "wsh(sortedmulti(2,[01020304/48'/0'/0'/2']$originalKey/0/*,[05060708/48'/0'/1'/2']$aliasedKey/1/*))"
        val policy = requireNotNull(WalletInfoViewModel.parseMultisigPolicyForDisplay(descriptor, descriptor.replace("/0/*", "/1/*")))
        assertEquals(descriptor, policy.descriptor)
        assertEquals(2, policy.threshold)
        assertEquals(2, policy.keystores.size)
        assertEquals(aliasedKey, policy.keystores[1].xpub)
        assertTrue(policy.warnings.single().contains("Do not count these as independent signers"))
        assertTrue(policy.warnings.single().contains("Keep the original descriptor"))
    }

    @Test
    fun `legacy descriptor backup retains duplicate signer recovery warning`() {
        val descriptor = "wsh(multi(2,[01020304/48'/0'/0'/2']$originalKey/0/*,[05060708/48'/0'/1'/2']$aliasedKey/0/*))"
        val wallet = WalletData(id = "legacy-wallet", name = "Legacy vault", descriptor = descriptor,
            changeDescriptor = descriptor.replace("/0/*", "/1/*"), isWatchOnly = true, isMultisig = true)
        val metadata = WalletInfoViewModel.buildDescriptorBackupMetadata(wallet)
        assertEquals(descriptor, wallet.descriptor)
        assertTrue(metadata.signerWarnings.single().contains("share the same key material"))
        assertNotNull(metadata.bsmsDescriptorRecord)
    }

    @Test
    fun `different chain codes do not produce duplicate material warning`() {
        val descriptor = "wsh(multi(2,[01020304/48'/0'/0'/2']$originalKey/0/*,[05060708/48'/0'/1'/2']$distinctKey/0/*))"
        val policy = requireNotNull(WalletInfoViewModel.parseMultisigPolicyForDisplay(descriptor, descriptor.replace("/0/*", "/1/*")))
        assertTrue(policy.warnings.isEmpty())
    }

    @Test
    fun `renaming legacy cosigners cannot remove the material warning`() {
        val descriptor = "wsh(multi(2,[01020304/48'/0'/0'/2']$originalKey/0/*,[05060708/48'/0'/1'/2']$aliasedKey/0/*))"
        val policy = requireNotNull(WalletInfoViewModel.parseMultisigPolicyForDisplay(descriptor, descriptor.replace("/0/*", "/1/*")))
        val renamed = policy.keystores.mapIndexed { i, key -> key.copy(label = "Device ${i + 1}") }
        val warning = WalletInfoViewModel.buildMultisigWarnings(renamed).single()
        assertTrue(warning.startsWith("Device 1, Device 2 share"))
    }

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
        assertTrue(policy.keystores.all { it.keyId.isNotBlank() })
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
    fun `keystore id is stable for same signer material`() {
        val first = WalletInfoViewModel.stableKeystoreId(
            fingerprint = "aabbccdd",
            derivationPath = "48'/0'/0'/2'",
            xpub = "xpub6Alpha"
        )
        val second = WalletInfoViewModel.stableKeystoreId(
            fingerprint = "AABBCCDD",
            derivationPath = "48'/0'/0'/2'",
            xpub = "xpub6Alpha"
        )

        assertEquals(first, second)
    }

    @Test
    fun `nested segwit multisig descriptor gets nested script label`() {
        val descriptor =
            "sh(wsh(multi(2," +
                "[aabbccdd/48'/0'/0'/1']xpub6Alpha/0/*," +
                "[11223344/48'/0'/0'/1']xpub6Bravo/0/*" +
                ")))"

        val policy = requireNotNull(WalletInfoViewModel.parseMultisigPolicyForDisplay(descriptor, descriptor.replace("/0/*", "/1/*")))

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

        assertNull(WalletInfoViewModel.parseMultisigPolicyForDisplay(descriptor, descriptor.replace("/0/*", "/1/*")))
    }

    @Test
    fun `multisig descriptor with no keystores returns null policy`() {
        val descriptor = "wsh(sortedmulti(1))"

        assertNull(WalletInfoViewModel.parseMultisigPolicyForDisplay(descriptor, descriptor.replace("/0/*", "/1/*")))
    }

    @Test
    fun `BSMS descriptor record round trips through multisig parser`() {
        val descriptor =
            "wsh(sortedmulti(2," +
                "[aabbccdd/48'/0'/0'/2']xpub6Alpha/0/*," +
                "[11223344/48'/0'/0'/2']xpub6Bravo/0/*" +
                "))"
        val policy = requireNotNull(WalletInfoViewModel.parseMultisigPolicyForDisplay(descriptor, descriptor.replace("/0/*", "/1/*")))

        assertEquals(descriptor, MultisigWalletConfigParser.parse(policy.bsmsDescriptorRecord))
    }

    @Test
    fun `keystore without origin exposes health warnings`() {
        val descriptor = "wsh(sortedmulti(1,xpub6Alpha/0/*))"

        val policy = requireNotNull(WalletInfoViewModel.parseMultisigPolicyForDisplay(descriptor, descriptor.replace("/0/*", "/1/*")))

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

    @Test
    fun `multisig descriptor stays classified as multisig when policy parsing fails`() {
        val descriptor =
            "wsh(sortedmulti(3," +
                "[aabbccdd/48'/0'/0'/2']xpub6Alpha/0/*," +
                "[11223344/48'/0'/0'/2']xpub6Bravo/0/*" +
                "))"
        val wallet = WalletData(
            id = "wallet-1",
            name = "Vault",
            descriptor = descriptor,
            changeDescriptor = descriptor.replace("/0/*", "/1/*"),
            isWatchOnly = true,
            isMultisig = false
        )

        assertNull(WalletInfoViewModel.parseMultisigPolicyForDisplay(descriptor, descriptor.replace("/0/*", "/1/*")))
        assertTrue(DescriptorDisplayPolicy.isMultisigDescriptor(descriptor))
        assertTrue(WalletInfoViewModel.buildDescriptorBackupMetadata(wallet).isMultisig)
    }

    @Test
    fun `single sig descriptor is not classified as multisig`() {
        assertTrue(!DescriptorDisplayPolicy.isMultisigDescriptor("wpkh([aabbccdd/84'/0'/0']xpub6Alpha/0/*)"))
    }
}
