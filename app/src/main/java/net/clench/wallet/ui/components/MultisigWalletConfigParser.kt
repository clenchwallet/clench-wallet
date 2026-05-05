package net.clench.wallet.ui.components

/**
 * Parses common watch-only multisig wallet configuration exports into a BIP-380
 * descriptor Clench can import through the existing descriptor path.
 */
object MultisigWalletConfigParser {
    private val descriptorStart = Regex("""(?:^|[\s:=])((?:sh\(wsh\(|wsh\(|sh\()).*""")
    private val policyRegex = Regex("""(?i)^policy\s*:\s*(\d+)\s*(?:of|/)\s*(\d+)\s*$""")
    private val formatRegex = Regex("""(?i)^format\s*:\s*(.+)$""")
    private val derivationRegex = Regex("""(?i)^derivation\s*:\s*(.+)$""")
    private val keyRegex = Regex("""^([0-9a-fA-F]{8})\s*:\s*([A-Za-z0-9]+).*$""")

    fun parse(text: String): String? {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isBlank()) return null

        parseBsmsDescriptor(normalized)?.let { return it }
        parseDescriptorLine(normalized)?.let { return it }
        parseColdcardConfig(normalized)?.let { return it }
        return null
    }

    private fun parseBsmsDescriptor(text: String): String? {
        val lines = significantLines(text)
        val markerIndex = lines.indexOfFirst { it.equals("BSMS 1.0", ignoreCase = true) }
        if (markerIndex < 0 || markerIndex + 1 >= lines.size) return null

        val descriptor = parseDescriptorLine(lines[markerIndex + 1]) ?: return null
        val restrictions = lines.getOrNull(markerIndex + 2).orEmpty()
        return applyBsmsPathRestrictions(descriptor, restrictions)
    }

    private fun applyBsmsPathRestrictions(descriptor: String, restrictions: String): String {
        if (!descriptor.contains("/**")) return descriptor
        val externalRestriction = restrictions
            .split(',')
            .map { it.trim() }
            .firstOrNull { it.startsWith("/0/") || it == "/0/*" }
            ?: "/0/*"
        return descriptor
            .substringBefore("#")
            .replace("/**", externalRestriction)
    }

    private fun parseDescriptorLine(text: String): String? {
        val candidates = buildList {
            add(text.trim())
            text.lineSequence().forEach { line ->
                val trimmed = line.trim()
                add(trimmed)
                add(trimmed.substringAfter(':', trimmed).trim())
                descriptorStart.find(trimmed)?.groupValues?.getOrNull(1)?.let { add(it.trim()) }
            }
        }

        return candidates
            .mapNotNull { extractDescriptor(it) }
            .firstOrNull { isMultisigDescriptor(it) }
    }

    private fun extractDescriptor(text: String): String? {
        val trimmed = text.trim().trim('"', '\'')
        val start = listOf("sh(wsh(", "wsh(", "sh(")
            .map { trimmed.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
            ?: return null
        val descriptor = balancedDescriptor(trimmed.substring(start)) ?: return null
        val checksum = trimmed.substring(start + descriptor.length).let { tail ->
            Regex("""^#[a-z0-9]{8}""", RegexOption.IGNORE_CASE)
                .find(tail)
                ?.value
                .orEmpty()
        }
        return descriptor + checksum
    }

    private fun balancedDescriptor(text: String): String? {
        var depth = 0
        text.forEachIndexed { index, char ->
            when (char) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(0, index + 1)
                    if (depth < 0) return null
                }
            }
        }
        return null
    }

    private fun parseColdcardConfig(text: String): String? {
        val lines = significantLines(text)
        val policy = lines.firstNotNullOfOrNull { line ->
            policyRegex.find(line)?.let { match ->
                val threshold = match.groupValues[1].toIntOrNull()
                val signerCount = match.groupValues[2].toIntOrNull()
                if (threshold != null && signerCount != null) threshold to signerCount else null
            }
        } ?: return null

        val format = lines.firstNotNullOfOrNull { line ->
            formatRegex.find(line)?.groupValues?.getOrNull(1)
        }.orEmpty()

        var currentDerivation: String? = null
        val keys = mutableListOf<String>()
        lines.forEach { line ->
            derivationRegex.find(line)?.let { match ->
                currentDerivation = normalizeDerivationPath(match.groupValues[1])
                return@forEach
            }

            keyRegex.find(line)?.let { match ->
                val fingerprint = match.groupValues[1].uppercase()
                val xpub = match.groupValues[2]
                val derivation = currentDerivation ?: return@forEach
                keys += "[$fingerprint/$derivation]$xpub/0/*"
            }
        }

        val (threshold, signerCount) = policy
        if (threshold !in 1..signerCount || keys.size != signerCount) return null

        val inner = "sortedmulti($threshold,${keys.joinToString(",")})"
        return when (normalizeFormat(format)) {
            "p2sh-p2wsh" -> "sh(wsh($inner))"
            "p2sh" -> "sh($inner)"
            else -> "wsh($inner)"
        }
    }

    private fun normalizeFormat(format: String): String {
        return format
            .lowercase()
            .replace(" ", "")
            .replace("_", "-")
            .replace("pw2sh", "p2wsh")
    }

    private fun normalizeDerivationPath(raw: String): String {
        return raw
            .trim()
            .removePrefix("m/")
            .removePrefix("/")
            .replace("h", "'")
            .replace("H", "'")
    }

    private fun significantLines(text: String): List<String> {
        return text
            .lineSequence()
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun isMultisigDescriptor(descriptor: String): Boolean {
        val lower = descriptor.lowercase()
        return lower.contains("multi(") || lower.contains("sortedmulti(")
    }
}
