package net.clench.wallet.data.util

import net.clench.wallet.data.local.entity.TransactionLabelEntity
import org.json.JSONObject

object Bip329 {

    /**
     * Export transaction labels to BIP-329 JSONL format.
     * Each line is a JSON object: {"type":"tx","ref":"<txid>","label":"<label>"}
     */
    fun exportLabels(labels: List<TransactionLabelEntity>): String {
        return labels.joinToString("\n") { entity ->
            val obj = JSONObject()
            obj.put("type", "tx")
            obj.put("ref", entity.txid)
            obj.put("label", entity.label)
            obj.toString()
        }
    }

    /**
     * Import BIP-329 JSONL string. Returns list of (txid, label) pairs.
     * Only processes type "tx"; gracefully skips unknown types and malformed lines.
     */
    fun importLabels(jsonl: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        for (line in jsonl.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            try {
                val obj = JSONObject(trimmed)
                val type = obj.optString("type", "")
                if (type != "tx") continue
                val ref = obj.optString("ref", "")
                val label = obj.optString("label", "")
                if (ref.isNotEmpty() && label.isNotEmpty()) {
                    results.add(ref to label)
                }
            } catch (_: Exception) {
                // Skip malformed lines
            }
        }
        return results
    }
}
