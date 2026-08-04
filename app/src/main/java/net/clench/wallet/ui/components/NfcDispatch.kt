package net.clench.wallet.ui.components

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.PatternMatcher

object NfcDispatch {
    fun enableCoinkiteForegroundDispatch(activity: Activity, adapter: NfcAdapter) {
        adapter.enableForegroundDispatch(
            activity,
            pendingIntent(activity),
            coinkiteIntentFilters(),
            arrayOf(arrayOf(IsoDep::class.java.name))
        )
    }

    fun disableForegroundDispatch(activity: Activity, adapter: NfcAdapter) {
        runCatching { adapter.disableForegroundDispatch(activity) }
    }

    private fun pendingIntent(activity: Activity): PendingIntent {
        val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getActivity(
            activity,
            0,
            Intent(activity, activity.javaClass)
                .setAction(NfcAdapter.ACTION_TAG_DISCOVERED)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag
        )
    }

    private fun coinkiteIntentFilters(): Array<IntentFilter> {
        val filters = mutableListOf<IntentFilter>()
        for (host in COINKITE_TAP_HOSTS) {
            filters += coinkiteUrlFilter(NfcAdapter.ACTION_NDEF_DISCOVERED, host)
        }
        filters += IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        filters += IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        return filters.toTypedArray()
    }

    private fun coinkiteUrlFilter(action: String, host: String): IntentFilter {
        return IntentFilter(action).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("https")
            addDataAuthority(host, null)
            addDataPath("/start", PatternMatcher.PATTERN_PREFIX)
        }
    }

    private val COINKITE_TAP_HOSTS = listOf(
        "tapsigner.com",
        "satscard.com",
        "getsatscard.com"
    )
}
