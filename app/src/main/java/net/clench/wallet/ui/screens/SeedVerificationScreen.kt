package net.clench.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.clench.wallet.ui.util.SecureWindowEffect
import net.clench.wallet.ui.viewmodel.CreateWalletViewModel

/**
 * Seed Verification Quiz screen.
 * After showing the seed phrase, quiz the user on 3-4 random words
 * using word chip selectors before allowing wallet creation.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SeedVerificationScreen(
    onVerified: () -> Unit,
    onBack: () -> Unit,
    viewModel: CreateWalletViewModel
) {
    SecureWindowEffect()

    val uiState by viewModel.uiState.collectAsState()
    val mnemonic = uiState.mnemonic

    // Pick 4 random word positions to quiz (stable across recomposition)
    val quizPositions = remember(mnemonic) {
        if (mnemonic.size < 4) emptyList()
        else mnemonic.indices.toList().shuffled().take(4).sorted()
    }

    // Generate 6 candidate words for each quiz position (including the correct one)
    val candidateWords = remember(mnemonic, quizPositions) {
        quizPositions.map { pos ->
            val correct = mnemonic[pos]
            // Pick 5 random wrong words from the mnemonic (or BIP39-like words)
            val others = mnemonic.filterIndexed { i, _ -> i != pos }
                .distinct()
                .shuffled()
                .take(5)
            (others + correct).distinct().shuffled()
        }
    }

    // Track user selections: position index -> selected word (null = not yet selected)
    val selections = remember(quizPositions) {
        mutableStateMapOf<Int, String>()
    }

    // Track which ones have been checked and found wrong
    var checkedOnce by remember { mutableStateOf(false) }

    val allCorrect = quizPositions.indices.all { i ->
        selections[i] == mnemonic[quizPositions[i]]
    }

    val allSelected = selections.size == quizPositions.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verify Seed Phrase") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Info card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE3F2FD)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Verify you've written down your seed phrase correctly. " +
                    "Select the correct word for each position.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF1565C0)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Quiz questions
            quizPositions.forEachIndexed { quizIndex, wordPosition ->
                val correctWord = mnemonic[wordPosition]
                val selected = selections[quizIndex]
                val isWrong = checkedOnce && selected != null && selected != correctWord

                Text(
                    "Word #${wordPosition + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Word chips
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    candidateWords[quizIndex].forEach { word ->
                        val isSelected = selected == word
                        val isCorrectSelection = isSelected && word == correctWord
                        val isWrongSelection = isSelected && isWrong

                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selections[quizIndex] = word
                                // Reset error state when user changes selection
                                if (checkedOnce) checkedOnce = false
                            },
                            label = { Text(word) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = when {
                                    isWrongSelection -> MaterialTheme.colorScheme.errorContainer
                                    checkedOnce && isCorrectSelection -> Color(0xFFC8E6C9)
                                    isSelected -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surface
                                },
                                selectedLabelColor = when {
                                    isWrongSelection -> MaterialTheme.colorScheme.onErrorContainer
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        )
                    }
                }

                if (isWrong) {
                    Text(
                        "Incorrect — try again",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Continue button
            Button(
                onClick = {
                    checkedOnce = true
                    if (allCorrect) {
                        onVerified()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = allSelected
            ) {
                Text("Continue")
            }

            if (checkedOnce && !allCorrect) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Some answers are incorrect. Please review your seed phrase and try again.",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Go Back button
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Go Back to Review Seed")
            }
        }
    }
}
