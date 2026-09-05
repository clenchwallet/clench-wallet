package net.clench.wallet.data.local

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.clench.wallet.security.AuthenticationGate
import net.clench.wallet.security.AuthenticationGateChangeController
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthenticationGatePersistenceTest {
    private fun isolated(block: (SettingsManager) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "gate-regression-${UUID.randomUUID()}"
        val wrapper = object : ContextWrapper(target) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
                target.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
        try { block(SettingsManager(wrapper)) } finally { target.deleteSharedPreferences(name) }
    }

    @Test fun initialDefaultsNeverOverwritePreviouslyEnabledProtection() = isolated { manager ->
        manager.initializeAuthenticationGates(true)
        manager.initializeAuthenticationGates(false)
        assertTrue(manager.isBiometricForSeedEnabled())
        assertTrue(manager.isBiometricForSendEnabled())
    }

    @Test fun previouslyConfiguredSingleGatePreventsDefaultDowngrade() = isolated { manager ->
        manager.setBiometricForSeedEnabled(true)
        manager.initializeAuthenticationGates(false)
        assertTrue(manager.isBiometricForSeedEnabled())
        assertTrue(manager.isBiometricForSendEnabled())
    }

    @Test fun initialUnavailableAuthDefaultsAreOneTimeOnly() = isolated { manager ->
        manager.initializeAuthenticationGates(false)
        assertFalse(manager.isBiometricForSeedEnabled())
        manager.setBiometricForSeedEnabled(true)
        manager.initializeAuthenticationGates(false)
        assertTrue(manager.isBiometricForSeedEnabled())
    }

    @Test fun cancelledControllerDoesNotPersistDowngrade() = isolated { manager ->
        manager.initializeAuthenticationGates(true)
        val controller = AuthenticationGateChangeController(
            { manager.isBiometricForSeedEnabled() },
            { _, enabled -> manager.setBiometricForSeedEnabled(enabled) }
        )
        var success: () -> Unit = {}
        controller.request(AuthenticationGate.SEED, false) { ok, _ -> success = ok }
        assertTrue(manager.isBiometricForSeedEnabled())
        controller.cancel(); success()
        assertTrue(manager.isBiometricForSeedEnabled())
        controller.request(AuthenticationGate.SEED, false) { ok, _ -> success = ok }
        success()
        assertFalse(manager.isBiometricForSeedEnabled())
    }
}
