package net.clench.wallet.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BIP-78 PayJoin client (sender/requester side).
 *
 * Sends the original PSBT to the receiver's PayJoin endpoint and
 * returns the receiver's payjoin proposal PSBT.
 */
@Singleton
class PayJoinClient @Inject constructor(
    private val torAwareHttpClient: TorAwareHttpClient
) {
    companion object {
        private const val TAG = "PayJoinClient"
    }

    /**
     * Send original PSBT to a PayJoin endpoint and get back the proposal.
     *
     * @param endpoint The BIP-21 pj= URL (HTTPS or .onion)
     * @param originalPsbt The original PSBT as base64
     * @return The payjoin proposal PSBT as base64
     * @throws PayJoinException on protocol-level or network errors
     */
    suspend fun requestPayJoin(endpoint: String, originalPsbt: String): String = withContext(Dispatchers.IO) {
        // BIP-78: endpoint MUST be HTTPS or .onion
        val isSecure = endpoint.startsWith("https://", ignoreCase = true) ||
                endpoint.contains(".onion", ignoreCase = true)
        if (!isSecure) {
            throw PayJoinException("PayJoin endpoint must be HTTPS or .onion: $endpoint")
        }

        Log.d(TAG, "Requesting PayJoin from endpoint (length=${endpoint.length})")
        // Never log the PSBT content or full endpoint for privacy

        try {
            val response = torAwareHttpClient.postText(
                url = endpoint,
                body = originalPsbt,
                contentType = "text/plain",
                connectTimeoutMs = 15_000,
                readTimeoutMs = 30_000
            )

            val proposal = response.trim()
            if (proposal.isEmpty()) {
                throw PayJoinException("Empty response from PayJoin endpoint")
            }

            Log.d(TAG, "PayJoin proposal received (response length=${proposal.length})")
            proposal
        } catch (e: PayJoinException) {
            throw e
        } catch (e: java.io.IOException) {
            Log.w(TAG, "PayJoin network error: ${e.message}")
            throw PayJoinException("PayJoin endpoint unreachable: ${e.message}", e)
        } catch (e: Exception) {
            Log.w(TAG, "PayJoin request failed: ${e.message}")
            throw PayJoinException("PayJoin request failed: ${e.message}", e)
        }
    }
}

/**
 * Exception for PayJoin protocol errors.
 * Callers should catch this and fall back to broadcasting the original transaction.
 */
class PayJoinException(message: String, cause: Throwable? = null) : Exception(message, cause)
