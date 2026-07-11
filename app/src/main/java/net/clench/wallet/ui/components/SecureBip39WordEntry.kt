package net.clench.wallet.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal object Bip39WordPicker {
    val supportedWordCounts = setOf(12, 24)

    fun normalize(words: List<String>, wordList: Set<String>, limit: Int): List<String> =
        words.asSequence()
            .map { it.trim().lowercase() }
            .filter { it in wordList }
            .take(limit)
            .toList()

    fun suggestions(prefix: String, wordList: List<String>, limit: Int = 8): List<String> {
        val normalized = prefix.trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        return wordList.asSequence().filter { it.startsWith(normalized) }.take(limit).toList()
    }

    fun isComplete(words: List<String>, expectedWordCount: Int): Boolean =
        expectedWordCount in supportedWordCounts && words.size == expectedWordCount
}

@Composable
fun rememberBip39EnglishWords(): List<String> {
    val context = LocalContext.current
    return remember(context) {
        context.assets.open("bip39_english.txt").bufferedReader().use { reader ->
            reader.readLines().map(String::trim).filter(String::isNotBlank)
        }.also { check(it.size == 2048) { "Bundled BIP39 English word list is incomplete" } }
    }
}

/**
 * Seed-word entry that never opens an Android input method. Words are selected from
 * the bundled canonical BIP39 list with an on-screen prefix keypad.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SecureBip39WordEntry(
    words: List<String>,
    expectedWordCount: Int,
    onWordsChange: (List<String>) -> Unit,
    onExpectedWordCountChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Enter recovery words securely"
) {
    val wordList = rememberBip39EnglishWords()
    val wordSet = remember(wordList) { wordList.toHashSet() }
    var prefix by remember { mutableStateOf("") }
    val normalizedWords = remember(words, expectedWordCount, wordSet) {
        Bip39WordPicker.normalize(words, wordSet, expectedWordCount)
    }
    val suggestions = remember(prefix, wordList) { Bip39WordPicker.suggestions(prefix, wordList) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Use the on-screen word picker. Android's keyboard, suggestions, clipboard, and autofill are not used.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Bip39WordPicker.supportedWordCounts.sorted().forEach { count ->
                    FilterChip(
                        selected = expectedWordCount == count,
                        onClick = {
                            prefix = ""
                            onExpectedWordCountChange(count)
                            if (words.size > count) onWordsChange(words.take(count))
                        },
                        label = { Text("$count words") }
                    )
                }
            }

            Text(
                "${normalizedWords.size} of $expectedWordCount words selected",
                style = MaterialTheme.typography.labelLarge
            )
            if (normalizedWords.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    normalizedWords.forEachIndexed { index, word ->
                        AssistChip(
                            onClick = { onWordsChange(normalizedWords.filterIndexed { i, _ -> i != index }) },
                            label = { Text("${index + 1}. $word") }
                        )
                    }
                }
                Text(
                    "Tap a selected word to remove it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (normalizedWords.size < expectedWordCount) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(
                            prefix.ifBlank { "Choose letters for word ${normalizedWords.size + 1}" },
                            modifier = Modifier.weight(1f),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (prefix.isNotEmpty()) {
                            IconButton(onClick = { prefix = prefix.dropLast(1) }) {
                                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Remove last letter")
                            }
                        }
                    }
                }

                if (suggestions.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        suggestions.forEach { suggestion ->
                            Button(
                                onClick = {
                                    onWordsChange(normalizedWords + suggestion)
                                    prefix = ""
                                }
                            ) { Text(suggestion) }
                        }
                    }
                } else if (prefix.isNotEmpty()) {
                    Text(
                        "No BIP39 word starts with “$prefix”. Remove a letter and try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ('a'..'z').forEach { letter ->
                        OutlinedButton(
                            onClick = { prefix += letter },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) { Text(letter.uppercaseChar().toString()) }
                    }
                }
            } else {
                Text(
                    "All $expectedWordCount words selected. Clench will validate the BIP39 checksum before import.",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        prefix = ""
                        onWordsChange(emptyList())
                    },
                    enabled = normalizedWords.isNotEmpty()
                ) { Text("Clear all") }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (Bip39WordPicker.isComplete(normalizedWords, expectedWordCount)) "Ready to validate" else "Incomplete",
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
