package net.clench.wallet.data.repository

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import net.clench.wallet.ui.components.MultisigWalletConfigParser
import net.clench.wallet.verification.VerificationPropertyHarness
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MultisigDescriptorHostilePropertyTest {
    @Test
    fun `generated unique multisig policies satisfy descriptor invariants`() {
        VerificationPropertyHarness.forAll(seed = 0x4D554C5449534947L) { random, caseIndex ->
            val signerCount = random.nextInt(14) + 2
            val threshold = random.nextInt(signerCount) + 1
            val keys = (0 until signerCount).map { signer ->
                "[${(caseIndex + signer).toString(16).padStart(8, '0')}/48'/1'/0'/2']" +
                    "tpub${caseIndex.toString(36)}Signer${signer}/0/*"
            }
            val descriptor = "wsh(sortedmulti($threshold,${keys.joinToString(",")}))"

            MultisigDescriptorSafety.validate(descriptor)
            assertTrue(MultisigWalletConfigParser.parse(descriptor)?.startsWith("wsh(sortedmulti(") == true)
        }
    }

    @Test
    fun `same cosigner under different origin metadata is rejected`() {
        val descriptor = "wsh(sortedmulti(2," +
            "[AAAAAAAA/48'/1'/0'/2']tpubDuplicate/0/*," +
            "[BBBBBBBB/48'/1'/0'/2']tpubDuplicate/0/*," +
            "[CCCCCCCC/48'/1'/0'/2']tpubIndependent/0/*))"

        assertThrows(IllegalArgumentException::class.java) {
            MultisigDescriptorSafety.validate(descriptor)
        }
    }

    @Test
    fun `base58 public keys remain case sensitive while derivation aliases are rejected`() {
        MultisigDescriptorSafety.validate(
            "wsh(sortedmulti(2,tpubCaseSensitive/0/*,tpubcasesensitive/0/*))"
        )
        assertThrows(IllegalArgumentException::class.java) {
            MultisigDescriptorSafety.validate(
                "wsh(sortedmulti(2,tpubSameRoot/0/*,tpubSameRoot/1/*))"
            )
        }
    }

    @Test
    fun `slip132 aliases of the same extended key are duplicate cosigners`() {
        val payload = ByteArray(74) { index -> (index + 1).toByte() }.also {
            it[41] = 0x02
        }
        val xpub = encodeExtendedKey(0x0488B21E, payload)
        val zpub = encodeExtendedKey(0x04B24746, payload)

        assertThrows(IllegalArgumentException::class.java) {
            MultisigDescriptorSafety.validate(
                "wsh(sortedmulti(2,$xpub/0/*,$zpub/1/*))"
            )
        }
    }

    @Test
    fun `invalid thresholds and excessive signer sets are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MultisigDescriptorSafety.validate("wsh(sortedmulti(3,tpubA/0/*,tpubB/0/*))")
        }
        val keys = (0..20).joinToString(",") { "tpubSigner$it/0/*" }
        assertThrows(IllegalArgumentException::class.java) {
            MultisigDescriptorSafety.validate("wsh(sortedmulti(2,$keys))")
        }
    }

    @Test
    fun `every multisig branch is checked for duplicate signers`() {
        val descriptor = "wsh(or_d(" +
            "multi(1,tpubFirst/0/*,tpubSecond/0/*)," +
            "multi(2,tpubDuplicate/0/*,tpubDuplicate/1/*)))"

        assertThrows(IllegalArgumentException::class.java) {
            MultisigDescriptorSafety.validate(descriptor)
        }
    }

    @Test
    fun `hostile multisig text corpus remains bounded`() {
        val alphabet = "wsh(sortedmulti,[]()/#'0123456789abcdefghijklmnopqrstuvwxyz"
        VerificationPropertyHarness.forAll(seed = 0x4D5346555A5AL) { random, _ ->
            val input = buildString(random.nextInt(4_096)) {
                repeat(random.nextInt(4_096)) {
                    append(alphabet[random.nextInt(alphabet.length)])
                }
            }
            VerificationPropertyHarness.assertNoFatalParserFailure {
                MultisigWalletConfigParser.parse(input)?.let(MultisigDescriptorSafety::validate)
            }
        }
    }

    private fun encodeExtendedKey(version: Int, payload: ByteArray): String {
        require(payload.size == 74)
        val serialized = ByteBuffer.allocate(78).putInt(version).put(payload).array()
        val digest = MessageDigest.getInstance("SHA-256")
        val checksum = digest.digest(digest.digest(serialized)).copyOfRange(0, 4)
        val complete = serialized + checksum
        var number = BigInteger(1, complete)
        val radix = BigInteger.valueOf(58)
        val encoded = StringBuilder()
        while (number > BigInteger.ZERO) {
            val parts = number.divideAndRemainder(radix)
            encoded.append(BASE58_ALPHABET[parts[1].toInt()])
            number = parts[0]
        }
        repeat(complete.takeWhile { it == 0.toByte() }.size) { encoded.append('1') }
        return encoded.reverse().toString()
    }

    private companion object {
        const val BASE58_ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    }
}
