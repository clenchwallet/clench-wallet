package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.model.WalletData
import net.clench.wallet.domain.repository.BitcoinRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Exercises the real startup model with distinct lifecycle owners, as required by
 * loading-entry scoping. These host tests do not execute Compose or prove that the
 * navigation host supplies the right owner; that binding also needs source review
 * and the focused foreground/re-entry emulator check.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupViewModelLifecycleTest {
    private val scheduler = TestCoroutineScheduler()
    private val owners = mutableListOf<ViewModelStoreOwner>()
    private val requests = mutableListOf<CompletableDeferred<List<WalletData>>>()
    private val repository = mockk<BitcoinRepository>()
    private val settings = mockk<SettingsManager>()
    private var onboarded = false
    private var lastViewedWalletId: String? = null
    private val unlockedWallets = mutableSetOf<String>()

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            check(modelClass == StartupViewModel::class.java)
            return StartupViewModel(repository, settings) as T
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        coEvery { repository.listWallets() } coAnswers {
            CompletableDeferred<List<WalletData>>().also { requests += it }.await()
        }
        every { repository.isPassphraseWalletUnlocked(any()) } answers {
            firstArg<String>() in unlockedWallets
        }
        every { settings.isOnboarded() } answers { onboarded }
        every { settings.getLastViewedWalletId() } answers { lastViewedWalletId }
    }

    @After
    fun teardown() {
        owners.forEach { it.viewModelStore.clear() }
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun newOwner(): ViewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }.also { owners += it }

    private fun model(owner: ViewModelStoreOwner): StartupViewModel =
        ViewModelProvider(owner, factory)[StartupViewModel::class.java]

    private fun wallet(id: String, passphrase: Boolean = false) = WalletData(
        id = id,
        name = "fixture-$id",
        descriptor = "unused-public-descriptor",
        changeDescriptor = "unused-public-change-descriptor",
        hasPassphrase = passphrase
    )

    private fun complete(request: Int, wallets: List<WalletData>) {
        assertTrue(requests[request].complete(wallets))
        scheduler.runCurrent()
    }

    @Test
    fun newLoadingOwnerWaitsForFreshWalletsInsteadOfReusingNetworkChoice() {
        val firstOwner = newOwner()
        val first = model(firstOwner)
        assertEquals(StartupViewModel.StartupDestination.Loading, first.destination.value)
        scheduler.runCurrent()
        complete(0, emptyList())
        assertEquals(StartupViewModel.StartupDestination.NetworkChoice, first.destination.value)

        // Reusing the old owner retains the stale answer: this is why navigation
        // must use a new loading entry instead of the Activity's persistent store.
        assertSame(first, model(firstOwner))
        firstOwner.viewModelStore.clear()
        onboarded = true
        lastViewedWalletId = "last-viewed"

        val resumed = model(newOwner())
        assertNotSame(first, resumed)
        assertEquals(StartupViewModel.StartupDestination.Loading, resumed.destination.value)
        assertTrue(resumed.wallets.value.isEmpty())
        scheduler.runCurrent()
        assertEquals(2, requests.size)
        assertEquals(StartupViewModel.StartupDestination.Loading, resumed.destination.value)

        val persisted = listOf(wallet("first"), wallet("last-viewed"))
        complete(1, persisted)
        assertEquals(persisted, resumed.wallets.value)
        assertEquals(
            StartupViewModel.StartupDestination.ExistingWallet("last-viewed", false),
            resumed.destination.value
        )
        coVerify(exactly = 2) { repository.listWallets() }
    }

    @Test
    fun freshOwnerPreservesLockedLastViewedPassphraseRoute() {
        onboarded = true
        lastViewedWalletId = "locked"
        val vm = model(newOwner())
        scheduler.runCurrent()
        complete(0, listOf(wallet("first"), wallet("locked", passphrase = true)))
        assertEquals(
            StartupViewModel.StartupDestination.ExistingWallet("locked", true),
            vm.destination.value
        )
    }

    @Test
    fun freshOwnerReadsCurrentPassphraseUnlockState() {
        onboarded = true
        lastViewedWalletId = "passphrase"
        unlockedWallets += "passphrase"
        val vm = model(newOwner())
        scheduler.runCurrent()
        complete(0, listOf(wallet("passphrase", passphrase = true)))
        assertEquals(
            StartupViewModel.StartupDestination.ExistingWallet("passphrase", false),
            vm.destination.value
        )
    }

    @Test
    fun missingLastViewedWalletFallsBackToFirstWithoutBypassingPassphraseGate() {
        onboarded = true
        lastViewedWalletId = "deleted-wallet"
        val vm = model(newOwner())
        scheduler.runCurrent()
        complete(0, listOf(wallet("first", passphrase = true), wallet("second")))
        assertEquals(
            StartupViewModel.StartupDestination.ExistingWallet("first", true),
            vm.destination.value
        )
    }

    @Test
    fun clearedPendingOwnerCannotPublishIntoNewOwnersLoad() {
        val oldOwner = newOwner()
        val old = model(oldOwner)
        scheduler.runCurrent()
        val oldJob = old.viewModelScope.coroutineContext[Job]!!
        oldOwner.viewModelStore.clear()
        scheduler.runCurrent()
        assertTrue(oldJob.isCancelled)

        onboarded = true
        lastViewedWalletId = "current"
        val current = model(newOwner())
        scheduler.runCurrent()
        assertEquals(2, requests.size)
        complete(0, listOf(wallet("obsolete")))
        assertEquals(StartupViewModel.StartupDestination.Loading, current.destination.value)
        assertTrue(current.wallets.value.isEmpty())

        complete(1, listOf(wallet("current")))
        assertEquals(
            StartupViewModel.StartupDestination.ExistingWallet("current", false),
            current.destination.value
        )
        assertEquals(listOf("current"), current.wallets.value.map { it.id })
    }

    @Test
    fun onboardedEmptyStoreRoutesToWelcome() {
        onboarded = true
        val vm = model(newOwner())
        scheduler.runCurrent()
        assertEquals(StartupViewModel.StartupDestination.Loading, vm.destination.value)
        complete(0, emptyList())
        assertEquals(StartupViewModel.StartupDestination.Welcome, vm.destination.value)
        assertTrue(vm.wallets.value.isEmpty())
    }
}
