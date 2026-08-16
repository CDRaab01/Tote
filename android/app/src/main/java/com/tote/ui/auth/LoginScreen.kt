package com.tote.ui.auth

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tote.ui.components.HazardRule
import com.tote.ui.components.ToteButton
import com.tote.ui.theme.ToteTheme
import com.tote.util.UiState
import design.pulse.ui.components.Caption
import design.pulse.ui.components.HeroPanel

@Composable
fun LoginScreen(viewModel: AuthViewModel = hiltViewModel()) {
    val state by viewModel.signInState.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> viewModel.onRedirect(result.data) }

    LoginContent(
        signInState = state,
        onSignIn = {
            viewModel.onSignInStarted()
            launcher.launch(viewModel.authorizeIntent())
        },
    )
}

/** Stateless body, so the screen can be rendered in a screenshot test without Hilt or AppAuth. */
@Composable
fun LoginContent(
    signInState: UiState<Unit>,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = ToteTheme.spacing
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(spacing.lg),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HeroPanel {
                Text("Tote", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                Spacer(Modifier.height(spacing.xs))
                Text(
                    "What's in the bins, and which bin it's in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.82f),
                )
                Spacer(Modifier.height(spacing.md))
                HazardRule()
            }

            Spacer(Modifier.height(spacing.xl))

            ToteButton(
                text = if (signInState is UiState.Loading) "Signing in…" else "Sign in with Dragonfly",
                onClick = onSignIn,
                enabled = signInState !is UiState.Loading,
            )

            if (signInState is UiState.Error) {
                Spacer(Modifier.height(spacing.md))
                Text(
                    signInState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(spacing.xl))
            // Honest about the reachability model rather than letting someone conclude the app
            // is broken when they are simply off the tailnet.
            Caption(text = "Tailnet only · one account across the suite")
        }
    }
}

@Preview(name = "Login — dark")
@Composable
private fun LoginDarkPreview() {
    ToteTheme(darkTheme = true) { LoginContent(UiState.Idle, {}) }
}

@Preview(name = "Login — light")
@Composable
private fun LoginLightPreview() {
    ToteTheme(darkTheme = false) { LoginContent(UiState.Idle, {}) }
}
