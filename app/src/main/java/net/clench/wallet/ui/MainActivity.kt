package net.clench.wallet.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import net.clench.wallet.ui.navigation.ClenchNavHost
import net.clench.wallet.ui.theme.ClenchTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClenchTheme {
                val navController = rememberNavController()
                ClenchNavHost(navController = navController)
            }
        }
    }
}
