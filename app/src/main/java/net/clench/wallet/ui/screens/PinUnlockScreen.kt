package net.clench.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import net.clench.wallet.data.local.PinManager
import net.clench.wallet.ui.util.SecureWindowEffect

@Composable
fun PinUnlockScreen(
    pinManager: PinManager,
    onUnlocked: () -> Unit
) {
    SecureWindowEffect()

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var remainingDelay by remember { mutableStateOf(0L) }

    // Poll remaining delay every second when throttled
    LaunchedEffect(remainingDelay) {
        if (remainingDelay > 0) {
            kotlinx.coroutines.delay(1000)
            remainingDelay = pinManager.getRemainingDelayMs()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Enter PIN", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 12 && it.all { c -> c.isDigit() }) pin = it },
            label = { Text("PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val err = pinManager.verifyPin(pin.toCharArray())
                if (err == null) {
                    onUnlocked()
                } else {
                    error = err
                    remainingDelay = pinManager.getRemainingDelayMs()
                    pin = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = pin.length >= PinManager.MIN_PIN_LENGTH && remainingDelay == 0L
        ) { Text("Unlock") }
    }
}
