package net.clench.wallet.verification

import java.util.Random
import org.junit.Assert.fail

internal object VerificationPropertyHarness {
    val defaultCases: Int
        get() = System.getenv("CLENCH_FUZZ_CASES")
            ?.toIntOrNull()
            ?.coerceIn(64, 20_000)
            ?: 512

    inline fun forAll(
        seed: Long,
        cases: Int = defaultCases,
        property: (random: Random, caseIndex: Int) -> Unit
    ) {
        val random = Random(seed)
        repeat(cases) { caseIndex ->
            try {
                property(random, caseIndex)
            } catch (failure: Throwable) {
                fail(
                    "Property failed at seed=$seed case=$caseIndex: " +
                        "${failure::class.java.simpleName}: ${failure.message}"
                )
            }
        }
    }

    fun assertNoFatalParserFailure(action: () -> Unit) {
        try {
            action()
        } catch (failure: VirtualMachineError) {
            throw AssertionError("Parser triggered a VM-level failure", failure)
        } catch (failure: LinkageError) {
            throw AssertionError("Parser triggered a linkage failure", failure)
        } catch (_: Exception) {
            // Rejection is an expected outcome for hostile generated inputs.
        }
    }

    fun Random.bytes(size: Int): ByteArray = ByteArray(size).also(::nextBytes)
}
