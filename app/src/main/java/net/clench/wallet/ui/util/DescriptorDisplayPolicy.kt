package net.clench.wallet.ui.util

object DescriptorDisplayPolicy {
    fun isMultisigDescriptor(descriptor: String): Boolean {
        val lower = descriptor.substringBefore("#").lowercase()
        return lower.contains("multi(") || lower.contains("sortedmulti(")
    }
}
