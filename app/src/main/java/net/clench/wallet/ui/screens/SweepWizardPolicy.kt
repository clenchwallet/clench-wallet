package net.clench.wallet.ui.screens

internal enum class SweepWizardStep(val label: String) {
    Source("Source"),
    Discovery("Discovery"),
    Destination("Destination"),
    Fee("Fee"),
    Review("Review")
}

internal object SweepWizardPolicy {
    fun destinationReady(
        destinationAddress: String,
        defaultDestinationAddress: String,
        externalDestinationConfirmed: Boolean
    ): Boolean = destinationAddress.isNotBlank() &&
        (destinationAddress.trim() == defaultDestinationAddress.trim() || externalDestinationConfirmed)

    fun seedReady(selectedWords: Int, expectedWords: Int): Boolean =
        expectedWords in setOf(12, 24) && selectedWords == expectedWords
}
