package net.clench.wallet.data.repository

import net.clench.wallet.domain.model.MultisigAccountKey
import net.clench.wallet.ui.viewmodel.WalletInfoViewModel
import net.clench.wallet.ui.viewmodel.WalletInfoViewModel.Companion.withMetadata
import net.clench.wallet.data.local.entity.WalletKeystoreMetadataEntity
import org.junit.Assert.*
import org.junit.Test

class MultisigCreationPolicyTest {
    private val a = "[01020304/48'/0'/0'/2']xpub6DYLKsxfR6wLthZeqQB6KeTfqqmyNkPZqmjTuJ4jMNeoBqfwvFax4VVTALMgWXegeDnU1JmnCL7sDYpAtVwhpDXXVcZugxxcXdu7ipEbCHV"
    private val b = "[05060708/48'/0'/1'/2']xpub6DYLKsxfR6wLti9GzcuLu5q4onJoiUfuZqVeZr2e1JsBnmpe9ybre1hSXHn52jtvgqGGfJE2k8sw5naQNwMU3auuueZzJDhm579cNQT3e9o"
    private val slip = "[01020304/48h/0h/0h/2h]zpub6u7i5vktX9WUnSp2i81QnF9vAU2KgmEKdYNX7zgr5aii6LynJqhEHuakvoTXdtC6ZSjQueD9Y3vJe6KqpNmTzdj4DRES9hDsNTXy8DujuY2"

    @Test fun `exact account grammar rejects extra signer and descriptor syntax`() {
        listOf("$a/0/*,$b", "$a,$b/1/*", "$a)", "wsh($a)", "$a/2/*", "$a#deadbeef", "$a/*", "$a/0/*/1/*").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { MultisigAccountKey.parse(it) }
        }
    }

    @Test fun `checksum private origin and wrong network fail closed`() {
        listOf(a.dropLast(1) + "W", a.replace("48'", "84'"), a.replace("/0'/0'", "/1'/0'"), a.replace("/2'", "/2"), a.replace("xpub", "xprv")).forEach {
            assertThrows(IllegalArgumentException::class.java) { MultisigAccountKey.parse(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { MultisigAccountKey.parse(a, true) }
    }

    @Test fun `suffixes and slip132 retain one exact signer identity`() {
        listOf(a, " $a/0/* ", "$a/1/*", "$a/**").forEach {
            assertEquals(a, MultisigAccountKey.parse(it).expression)
        }
        assertEquals(MultisigAccountKey.parse(a).identity, MultisigAccountKey.parse(slip).identity)
        assertTrue(MultisigAccountKey.parse(slip).expression.contains("]xpub"))
    }

    @Test fun `native policy contract rejects changed threshold signer count branch and origin`() {
        val expected = listOf(a, b)
        val good = "wsh(sortedmulti(2,$a/0/*,$b/0/*))"
        MultisigDescriptorSafety.requireExpectedPolicy(good + "#checksum", 2, expected, 0)
        MultisigDescriptorSafety.requireExpectedPolicy(good.replace("/0/*", "/1/*"), 2, expected, 1)
        listOf(good.replace("(2,", "(1,"), good.replace("," + b + "/0/*", "," + b + "/0/*," + a + "/0/*"),
            good.replace("/0/*", "/1/*"), good.replace("01020304", "deadbeef"), good.replace(b, slip)).forEach {
            assertThrows(IllegalArgumentException::class.java) { MultisigDescriptorSafety.requireExpectedPolicy(it, 2, expected, 0) }
        }
    }

    @Test fun `legacy metadata discrepancy is visible without rewriting effective descriptor`() {
        val receive = "wsh(sortedmulti(2,$a/0/*,$b/0/*))"
        val policy = requireNotNull(WalletInfoViewModel.parseMultisigPolicyForDisplay(receive, receive.replace("/0/*", "/1/*")))
        val metadata = mapOf(policy.keystores.first().keyId to WalletKeystoreMetadataEntity("fixture", policy.keystores.first().keyId, "Old signer", null, 0L))
        val reviewed = policy.withMetadata(metadata)
        assertEquals(receive, reviewed.descriptor)
        assertEquals(2, reviewed.totalSigners)
        assertTrue(reviewed.warnings.any { it.contains("Saved signer labels do not match") })
        assertTrue(requireNotNull(WalletInfoViewModel.parseMultisigPolicyForDisplay(receive, receive.replace("sortedmulti(2", "sortedmulti(1"))).warnings.any { it.contains("Receive and change policies differ") })
        assertTrue(policy.warnings.isEmpty())
    }
}
