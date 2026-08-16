package com.tote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tote.ui.navigation.ToteNavHost
import com.tote.ui.auth.AuthViewModel
import com.tote.ui.auth.LoginScreen
import com.tote.ui.theme.ToteTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ToteTheme {
                Gate()
            }
        }
    }
}

/**
 * Signed out → login; signed in → the app. Tote is SSO-only, so there is no third branch.
 *
 * The null case is a real state, not a default: the stored token is read asynchronously, and
 * treating "not yet known" as "signed out" would flash the login screen on every cold start for
 * a user who is already signed in.
 */
@Composable
private fun Gate(viewModel: AuthViewModel = hiltViewModel()) {
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    when (signedIn) {
        null -> Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        true -> ToteNavHost()
        false -> LoginScreen()
    }
}
