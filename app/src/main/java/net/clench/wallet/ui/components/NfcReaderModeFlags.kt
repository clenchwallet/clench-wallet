package net.clench.wallet.ui.components

import android.nfc.NfcAdapter

object NfcReaderModeFlags {
    val coinkiteTap: Int =
        NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

    val coldcardNdef: Int =
        NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

    val hardwareImport: Int =
        NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
}
