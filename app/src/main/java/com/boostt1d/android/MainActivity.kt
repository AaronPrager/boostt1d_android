package com.boostt1d.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boostt1d.android.dashboard.DashboardScreen
import com.boostt1d.android.data.AppState
import com.boostt1d.android.data.AppViewModel
import com.boostt1d.android.onboarding.OnboardingScreen
import com.boostt1d.android.profile.ProfileScreen
import com.boostt1d.android.ui.BoostT1DTheme
import com.boostt1d.android.ui.BoostTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BoostT1DTheme {
                BoostRoot()
            }
        }
    }
}

/**
 * Setup, then the dashboard.
 *
 * Routing is driven by the stored profile rather than by a navigation stack: with
 * three destinations, whether setup is finished is the only question worth asking,
 * and a nav library would be more moving parts than there are screens.
 */
@Composable
private fun BoostRoot(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showingProfile by remember { mutableStateOf(false) }

    when (val current = state) {
        // Storage has not answered yet. Rendering setup here would flash the wrong
        // screen at every returning user.
        AppState.Loading -> Box(
            modifier = Modifier.fillMaxSize().background(BoostTheme.colors.background),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = BoostTheme.colors.primary)
        }

        AppState.NeedsOnboarding -> OnboardingScreen(onFinished = { showingProfile = false })

        is AppState.Ready -> {
            if (showingProfile) {
                ProfileScreen(
                    profile = current.profile,
                    settings = current.settings,
                    onSave = viewModel::save,
                    onBack = { showingProfile = false },
                )
            } else {
                DashboardScreen(
                    profile = current.profile,
                    settings = current.settings,
                    onOpenProfile = { showingProfile = true },
                )
            }
        }
    }
}
