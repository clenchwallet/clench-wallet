package net.clench.wallet.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.clench.wallet.BuildConfig
import net.clench.wallet.data.local.SettingsManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real production MainActivity, Settings screen and OS authentication; no mocked callbacks. */
@RunWith(AndroidJUnit4::class)
class AuthenticationGateUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private lateinit var settings: SettingsManager

    @Before fun requireDisposableCredentialedEmulator() {
        check(BuildConfig.DEBUG)
        check(InstrumentationRegistry.getArguments().getString("clenchDisposableEmulator") == "YES") {
            "UI regression requires explicit disposable-emulator authorization"
        }
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Never run this fixture on a physical device" }
        check((context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure) {
            "The disposable emulator must have the fixture device credential configured"
        }
        settings = SettingsManager(context)
        settings.setOfflineMode(true)
        settings.setBtcPriceEnabled(false)
        settings.setExternalFeeLookupEnabled(false)
        settings.setOnboarded()
        settings.setAppLockMode("none")
        settings.setBiometricForSeedEnabled(true)
        settings.setBiometricForSendEnabled(true)
    }

    @Test fun seedGateRequiresSystemAuthenticationAndPersistsCancellation() = exerciseGate(seed = true)

    @Test fun sendGateRequiresSystemAuthenticationAndPersistsCancellation() = exerciseGate(seed = false)

    @Test fun backgroundingPendingAuthenticationPreservesEnabledGates() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                openSecurity()
                clickGate(seed = true)
                awaitSystemPrompt()
                shell("input keyevent KEYCODE_HOME")
                await("launcher after backgrounding") {
                    automation.rootInActiveWindow?.packageName?.toString() in
                        setOf("com.google.android.apps.nexuslauncher", "com.android.launcher3")
                }
                assertBothEnabled()
                shell("am start -W -n ${context.packageName}/${MainActivity::class.java.name}")
                await("foreground after pending authentication") {
                    automation.rootInActiveWindow?.packageName == context.packageName
                }
                assertBothEnabled()
                scenario.recreate()
                await("recreated app after backgrounding") {
                    automation.rootInActiveWindow?.packageName == context.packageName
                }
                assertBothEnabled()
            } catch (failure: Throwable) {
                saveHierarchy("background")
                throw failure
            }
        }
    }

    @Test fun unavailableAuthenticatorDoesNotWeakenExistingGates() {
        // Only the explicitly authorized disposable emulator's public fixture credential.
        shell("locksettings clear --old 246813")
        try {
            await("fixture credential removed") {
                !(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    openSecurity()
                    for (seed in listOf(true, false)) {
                        val toggle = findGate(seed)
                        assertFalse("Unavailable authentication must disable the downgrade control", toggle.isEnabled)
                        toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        assertBothEnabled()
                    }
                    scenario.recreate()
                    assertBothEnabled()
                } catch (failure: Throwable) {
                    saveHierarchy("unavailable")
                    throw failure
                }
            }
        } finally {
            shell("locksettings set-pin 246813")
            await("fixture credential restored") {
                (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
            }
        }
    }

    private fun exerciseGate(seed: Boolean) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                openSecurity()
                clickGate(seed)
                awaitSystemPrompt()
                assertBothEnabled()
                cancelSystemPrompt()
                assertBothEnabled()

                // Navigate away and reopen: cancellation must survive a new screen/VM visit.
                clickText("Back")
                clickText("Security")
                clickGate(seed)
                awaitSystemPrompt()
                assertBothEnabled()
                enterDeviceCredential()
                await("authenticated gate change") {
                    if (seed) !settings.isBiometricForSeedEnabled() else !settings.isBiometricForSendEnabled()
                }
                assertEquals(!seed, settings.isBiometricForSeedEnabled())
                assertEquals(seed, settings.isBiometricForSendEnabled())
                scenario.recreate()
                await("recreated application window") { automation.rootInActiveWindow?.packageName == context.packageName }
                assertEquals(!seed, settings.isBiometricForSeedEnabled())
                assertEquals(seed, settings.isBiometricForSendEnabled())
            } catch (failure: Throwable) {
                saveHierarchy(if (seed) "seed" else "send")
                throw failure
            }
        }
    }

    private fun openSecurity() {
        clickText("Settings")
        clickText("Security")
    }

    private fun assertBothEnabled() {
        assertTrue(settings.isBiometricForSeedEnabled())
        assertTrue(settings.isBiometricForSendEnabled())
    }

    private fun cancelSystemPrompt() {
        // Android may consume the first Back to hide the credential keyboard.
        // Do not mistake that for cancellation, or send Back into the app.
        repeat(2) {
            assertBothEnabled()
            if (automation.rootInActiveWindow?.packageName == context.packageName) return
            awaitSystemPrompt()
            shell("input keyevent KEYCODE_BACK")
            val deadline = SystemClock.uptimeMillis() + 1_500
            while (SystemClock.uptimeMillis() < deadline) {
                if (automation.rootInActiveWindow?.packageName == context.packageName) return
                SystemClock.sleep(100)
            }
        }
        await("return from cancelled prompt") { automation.rootInActiveWindow?.packageName == context.packageName }
    }

    private fun clickGate(seed: Boolean) {
        check(findGate(seed).performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "Could not click authentication gate"
        }
    }

    private fun findGate(seed: Boolean): AccessibilityNodeInfo {
        val label = if (seed) "Require authentication to view seed phrase" else "Require authentication to send"
        var found: AccessibilityNodeInfo? = null
        await("gate control: $label") {
            val nodes = nodes()
            val text = nodes.firstOrNull { it.text?.toString() == label && it.isVisibleToUser }
            if (text == null) {
                nodes.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                false
            } else {
                val bounds = android.graphics.Rect().also(text::getBoundsInScreen)
                val toggle = nodes.firstOrNull { candidate ->
                    val box = android.graphics.Rect().also(candidate::getBoundsInScreen)
                    candidate.isCheckable && candidate.isVisibleToUser &&
                        box.centerY() in (bounds.top - 32)..(bounds.bottom + 32)
                }
                found = toggle
                toggle != null
            }
        }
        return checkNotNull(found)
    }

    private fun clickText(label: String) = await("click $label") {
        val all = nodes()
        val found = all.firstOrNull { it.isVisibleToUser &&
            (it.text?.toString() == label || it.contentDescription?.toString() == label) }
        var target = found
        while (target != null && !target.isClickable) target = target.parent
        if (target == null) {
            all.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            false
        } else target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun awaitSystemPrompt() = await("real Android authentication window") {
        val root = automation.rootInActiveWindow
        val packageName = root?.packageName?.toString()
        packageName in setOf("com.android.systemui", "com.android.settings") &&
            nodes().any { it.isPassword || it.text?.contains("PIN", ignoreCase = true) == true ||
                it.text?.contains("authentication requirement", ignoreCase = true) == true }
    }

    private fun enterDeviceCredential() {
        // Emulator-only, public fixture credential provisioned by the CI job. Never a real PIN.
        val credentialButton = nodes().firstOrNull {
            it.text?.toString()?.let { text -> text.equals("Use PIN", true) || text.equals("Use screen lock", true) } == true
        }
        credentialButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        await("system credential entry") { nodes().any { it.isPassword || it.isEditable } }
        shell("input text 246813")
        shell("input keyevent KEYCODE_ENTER")
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)
        ).use { it.readBytes() }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || result.size >= 512) return
            result += node
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(automation.rootInActiveWindow)
        return result
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(200)
        }
        throw AssertionError("Timed out waiting for $label")
    }

    private fun saveHierarchy(label: String) {
        val output = File(context.getExternalFilesDir(null), "ui-regression").apply { mkdirs() }
        val hierarchy = nodes().joinToString("\n") {
            "${it.packageName} ${it.className} ${it.viewIdResourceName} " +
                if (it.isPassword) "[password omitted]" else "text=${it.text} description=${it.contentDescription}"
        }
        File(output, "$label-hierarchy.txt").writeText(hierarchy)
        // Gradle can uninstall the fixture before CI pulls external files.
        // Retain bounded password-redacted diagnostics; this test uses no wallet/seed.
        hierarchy.lineSequence().forEach { android.util.Log.i("ClenchUiRegression", "$label: $it") }
    }
}
