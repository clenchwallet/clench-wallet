package net.clench.wallet.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

internal const val TAPSIGNER_PIN_MIN_LENGTH = 6
internal const val TAPSIGNER_PIN_MAX_LENGTH = 32

/** Current firmware accepts numeric PINs; legacy firmware could retain printable-ASCII PINs. */
internal fun isValidTapsignerPin(pin: String): Boolean =
    pin.length in TAPSIGNER_PIN_MIN_LENGTH..TAPSIGNER_PIN_MAX_LENGTH &&
        pin.all { it.code in 0x21..0x7e }

internal fun isValidTapsignerPin(pin: CharArray): Boolean =
    pin.size in TAPSIGNER_PIN_MIN_LENGTH..TAPSIGNER_PIN_MAX_LENGTH &&
        pin.all { it.code in 0x21..0x7e }

internal fun tapsignerPinKeyboardType(useLegacyKeyboard: Boolean): KeyboardType =
    if (useLegacyKeyboard) KeyboardType.Password else KeyboardType.NumberPassword

internal fun dismissSoftwareKeyboard(
    focusManager: FocusManager,
    keyboardController: SoftwareKeyboardController?
) {
    focusManager.clearFocus(force = true)
    keyboardController?.hide()
}

@Composable
internal fun rememberImeDismissAction(): () -> Unit {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    return remember(focusManager, keyboardController) {
        { dismissSoftwareKeyboard(focusManager, keyboardController) }
    }
}

/**
 * Masked numeric-first TAPSIGNER PIN entry.
 *
 * Coinkite firmware since 1.0.1 accepts only numeric replacement PINs. The explicit legacy mode
 * preserves access to a card that retained an older printable-ASCII PIN.
 */
@Composable
internal fun TapsignerPinInput(
    value: String,
    onValueChange: (String) -> Unit,
    supportingText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false
) {
    var useLegacyKeyboard by rememberSaveable { mutableStateOf(false) }
    val dismissIme = rememberImeDismissAction()
    val hasInvalidCharacters = value.any { it.code !in 0x21..0x7e }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it.take(TAPSIGNER_PIN_MAX_LENGTH)) },
            label = { Text("TAPSIGNER PIN") },
            supportingText = {
                Text(
                    if (hasInvalidCharacters) {
                        "PINs must use printable ASCII characters without spaces."
                    } else {
                        supportingText
                    }
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = tapsignerPinKeyboardType(useLegacyKeyboard),
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { dismissIme() }),
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            isError = isError || hasInvalidCharacters,
            singleLine = true
        )
        TextButton(
            onClick = {
                dismissIme()
                useLegacyKeyboard = !useLegacyKeyboard
            },
            enabled = enabled
        ) {
            Text(
                if (useLegacyKeyboard) {
                    "Use number pad"
                } else {
                    "Use letters & symbols (legacy PIN)"
                }
            )
        }
    }
}
