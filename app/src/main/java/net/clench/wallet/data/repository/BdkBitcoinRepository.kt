package net.clench.wallet.data.repository

import net.clench.wallet.data.local.KeystoreManager
import net.clench.wallet.data.local.dao.TransactionDao
import net.clench.wallet.data.local.dao.WalletDao
import net.clench.wallet.domain.model.*
import net.clench.wallet.domain.repository.BitcoinRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BDK-backed implementation of BitcoinRepository.
 *
 * TODO: Wire up BDK calls in each method.
 *       BDK Android docs: https://bitcoindevkit.org/
 *       bdk-android: org.bitcoindevkit:bdk-android:1.1.0
 *
 * BDK Key Classes:
 *   - Mnemonic           → generate / restore seed phrases
 *   - DescriptorSecretKey → derive xprv from mnemonic
 *   - Descriptor         → build BIP84 descriptors (wpkh)
 *   - Wallet             → core wallet, balance, address derivation, tx building
 *   - ElectrumClient     → connect to Electrum server for sync
 *   - TxBuilder          → build and sign transactions
 *   - Psbt               → partially signed bitcoin transactions (for multisig)
 */
@Singleton
class BdkBitcoinRepository @Inject constructor(
    private val walletDao: WalletDao,
    private val transactionDao: TransactionDao,
    private val keystoreManager: KeystoreManager
) : BitcoinRepository {

    override suspend fun createWallet(
        name: String,
        wordCount: Int,
        passphrase: String?
    ): Pair<List<String>, WalletData> {
        // TODO: Implement with BDK
        // val mnemonic = Mnemonic(WordCount.WORDS24) // or WORDS12
        // val mnemonicWords = mnemonic.toString().split(" ")
        // val bip32RootKey = DescriptorSecretKey(Network.BITCOIN, mnemonic, passphrase ?: "")
        // val externalDescriptor = Descriptor("wpkh($bip32RootKey/84'/0'/0'/0/*)", Network.BITCOIN)
        // val changeDescriptor = Descriptor("wpkh($bip32RootKey/84'/0'/0'/1/*)", Network.BITCOIN)
        // ... persist and return
        throw NotImplementedError("BDK createWallet not yet implemented")
    }

    override suspend fun importWallet(
        name: String,
        mnemonic: List<String>,
        passphrase: String?
    ): WalletData {
        // TODO: Implement with BDK
        // val mnemonicObj = Mnemonic.fromString(mnemonic.joinToString(" "))
        // val bip32RootKey = DescriptorSecretKey(Network.BITCOIN, mnemonicObj, passphrase ?: "")
        // ... derive descriptors, persist, return
        throw NotImplementedError("BDK importWallet not yet implemented")
    }

    override suspend fun importWatchOnly(
        name: String,
        descriptor: String
    ): WalletData {
        // TODO: parse descriptor, create watch-only Wallet, persist
        throw NotImplementedError("BDK importWatchOnly not yet implemented")
    }

    override suspend fun syncWallet(walletId: String, config: ElectrumConfig): WalletBalance {
        // TODO:
        // val client = ElectrumClient("${config.serverUrl}:${config.port}")
        // val wallet = loadWallet(walletId)
        // val update = client.fullScan(wallet, stopGap, parallelRequests)
        // wallet.applyUpdate(update)
        // return wallet.balance().toWalletBalance()
        throw NotImplementedError("BDK syncWallet not yet implemented")
    }

    override suspend fun getBalance(walletId: String): WalletBalance {
        // TODO: load persisted balance from DB or last sync
        throw NotImplementedError("BDK getBalance not yet implemented")
    }

    override suspend fun getTransactions(walletId: String): List<TransactionItem> {
        // TODO: return from local Room cache (populated after sync)
        return transactionDao.getForWallet(walletId).map { entity ->
            TransactionItem(
                txid = entity.txid,
                amountSat = entity.amountSat,
                feeSat = entity.feeSat,
                timestamp = entity.timestampEpochMs?.let {
                    java.time.Instant.ofEpochMilli(it)
                },
                confirmations = entity.confirmations,
                direction = TxDirection.valueOf(entity.direction),
                address = entity.address
            )
        }
    }

    override suspend fun getReceiveAddress(walletId: String): Address {
        // TODO:
        // val wallet = loadWallet(walletId)
        // val addressInfo = wallet.revealNextAddress(KeychainKind.EXTERNAL)
        // return Address(addressInfo.address.toString(), addressInfo.index)
        throw NotImplementedError("BDK getReceiveAddress not yet implemented")
    }

    override suspend fun buildTransaction(
        walletId: String,
        toAddress: String,
        amountSat: Long?,
        feeRateSatPerVbyte: Float
    ): String {
        // TODO:
        // val wallet = loadWallet(walletId)
        // val psbt = if (amountSat == null) {
        //     TxBuilder().drainWallet().drainTo(Address(toAddress)).feeRate(feeRateSatPerVbyte).finish(wallet)
        // } else {
        //     TxBuilder().addRecipient(Address(toAddress).scriptPubkey(), amountSat.toULong()).feeRate(feeRateSatPerVbyte).finish(wallet)
        // }
        // wallet.sign(psbt)
        // return psbt.serialize()
        throw NotImplementedError("BDK buildTransaction not yet implemented")
    }

    override suspend fun broadcastTransaction(config: ElectrumConfig, txHex: String): String {
        // TODO:
        // val client = ElectrumClient("${config.serverUrl}:${config.port}")
        // val tx = Transaction(txHex)
        // client.broadcast(tx)
        // return tx.txid().toString()
        throw NotImplementedError("BDK broadcastTransaction not yet implemented")
    }

    override suspend fun listWallets(): List<WalletData> {
        return walletDao.getAll().map { entity ->
            WalletData(
                id = entity.id,
                name = entity.name,
                descriptor = entity.descriptor,
                changeDescriptor = entity.changeDescriptor,
                isWatchOnly = entity.isWatchOnly,
                isMultisig = entity.isMultisig,
                createdAt = java.time.Instant.ofEpochMilli(entity.createdAtEpochMs)
            )
        }
    }

    override suspend fun deleteWallet(walletId: String) {
        walletDao.deleteById(walletId)
        transactionDao.deleteForWallet(walletId)
        keystoreManager.deleteWalletSecrets(walletId)
    }
}
