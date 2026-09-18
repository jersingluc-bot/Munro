package uk.munromap

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import uk.munromap.data.Fix
import uk.munromap.data.LocationSource
import uk.munromap.data.MunroRepository
import uk.munromap.ui.MapScreen
import uk.munromap.ui.NearbyScreen

private val HillScheme = darkColorScheme(
    primary = Color(0xFF7FC4A8),
    onPrimary = Color(0xFF07281C),
    surface = Color(0xFF12191D),
    onSurface = Color(0xFFE2EAEE),
    background = Color(0xFF12191D),
    onBackground = Color(0xFFE2EAEE),
    surfaceVariant = Color(0xFF223038),
    onSurfaceVariant = Color(0xFFCADAE2),
    secondaryContainer = Color(0xFF274038),
    onSecondaryContainer = Color(0xFFD5EDE2),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = HillScheme) {
                MunroApp()
            }
        }
    }
}

@Composable
private fun MunroApp() {
    val context = LocalContext.current
    val munros = remember { MunroRepository.load(context) }

    var hasPermission by remember { mutableStateOf(LocationSource.hasPermission(context)) }
    var fix by remember { mutableStateOf<Fix?>(null) }
    var tab by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        hasPermission = granted.values.any { it }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            LocationSource.fixes(context).collect { fix = it }
        }
    }

    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("Map") },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("Nearest") },
                )
            }
            when (tab) {
                0 -> MapScreen(
                    munros = munros,
                    fix = fix,
                    hasPermission = hasPermission,
                    onRequestPermission = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                        )
                    },
                )
                else -> NearbyScreen(
                    munros = munros,
                    fix = fix,
                    hasPermission = hasPermission,
                )
            }
        }
    }
}
