package net.clench.wallet.ui.picker

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal interface PickerRoundTripHost {
    val pickerResume: StateFlow<PickerResume?>

    /** Returns false when security admission is closed or another picker is already pending. */
    fun launchPicker(request: PickerRequest): Boolean

    /** Abort only the exact outstanding request; stale screens cannot cancel a newer picker. */
    fun abortPicker(requestId: Long)

    /** Returns a result only for the current admitted foreground generation. */
    fun consumePickerResult(
        purpose: PickerPurpose,
        destination: PickerDestination
    ): PickerResult?
}

private object UnavailablePickerRoundTripHost : PickerRoundTripHost {
    override val pickerResume: StateFlow<PickerResume?> = MutableStateFlow(null)
    override fun launchPicker(request: PickerRequest): Boolean = false
    override fun abortPicker(requestId: Long) = Unit
    override fun consumePickerResult(
        purpose: PickerPurpose,
        destination: PickerDestination
    ): PickerResult? = null
}

internal val LocalPickerRoundTripHost = staticCompositionLocalOf<PickerRoundTripHost> {
    UnavailablePickerRoundTripHost
}
