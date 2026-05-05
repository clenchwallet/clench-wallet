package net.clench.wallet.ui.screens

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val crashLog = remember {
        // Try internal storage first
        val internal = try { File(context.filesDir, "crash_log.txt").readText() } catch (e: Exception) { null }
        if (!internal.isNullOrBlank()) return@remember internal

        // Fall back to external storage
        val external = try {
            File(context.getExternalFilesDir(null), "crash_log.txt").readText()
        } catch (e: Exception) { null }
        if (!external.isNullOrBlank()) return@remember external

        "No crash log found.\n\nThe app has not crashed yet, or the log was cleared.\n\nInstall the APK, let it crash, then reopen and come here."
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crash Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Clench Wallet Crash Log")
                            putExtra(Intent.EXTRA_TEXT, crashLog)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share crash log"))
                    }) { Text("Share") }

                    TextButton(onClick = {
                        try { File(context.filesDir, "crash_log.txt").delete() } catch (e: Exception) {}
                        try { File(context.getExternalFilesDir(null), "crash_log.txt").delete() } catch (e: Exception) {}
                    }) { Text("Clear") }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .horizontalScroll(rememberScrollState())
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = crashLog,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp
            )
        }
    }
}
