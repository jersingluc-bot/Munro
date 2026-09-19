package uk.munromap

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.munromap.data.BagStore
import uk.munromap.data.Fix
import uk.munromap.data.LocationSource
import uk.munromap.data.MapLayer
import uk.munromap.data.MapPack
import uk.munromap.data.MunroRepository
import uk.munromap.data.PackState
import uk.munromap.data.TileSource
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
            MaterialTheme(colorScheme = HillScheme) { MunroApp() }
        }
    }
}

@Composable
private fun MunroApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val munros = remember { MunroRepository.load(context) }
    val bagStore = remember { BagStore(context) }
    var bagged by remember { mutableStateOf(bagStore.load()) }

    var hasPermission by remember { mutableStateOf(LocationSource.hasPermission(context)) }
    var fix by remember { mutableStateOf<Fix?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var layer by remember { mutableStateOf(MapLayer.TERRAIN) }

    // One download state per layer.
    val packs = remember {
        mutableStateMapOf<MapLayer, PackState>().apply {
            MapLayer.entries.forEach { l ->
                put(l, MapPack.existingPath(context, l)?.let { PackState.Ready(it) } ?: PackState.Missing)
            }
        }
    }

    val state = packs[layer] ?: PackState.Missing

    // Open the tile database for whichever layer is showing.
    val tiles = remember(layer, state) {
        (state as? PackState.Ready)?.let { runCatching { TileSource(it.path) }.getOrNull() }
    }
    DisposableEffect(tiles) { onDispose { tiles?.close() } }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> hasPermission = granted.values.any { it } }

    LaunchedEffect(hasPermission) {
        if (hasPermission) LocationSource.fixes(context).collect { fix = it }
    }

    fun fetch(target: MapLayer) {
        scope.launch {
            packs[target] = PackState.Downloading(0, 0, 0)
            packs[target] = MapPack.download(context, target) { packs[target] = it }
        }
    }

    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {

            if (tab == 0) {
                LayerChips(
                    selected = layer,
                    stateFor = { packs[it] ?: PackState.Missing },
                    onSelect = { layer = it },
                )
                if (state is PackState.Ready) {
                    ReplaceLayerRow(
                        layer = layer,
                        sizeMb = MapPack.sizeMb(context, layer),
                        onReplace = { fetch(layer) },
                    )
                } else {
                    PackBanner(
                        layer = layer,
                        state = state,
                        onDownload = { fetch(layer) },
                    )
                }
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Map") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Nearest") })
            }

            when (tab) {
                0 -> MapScreen(
                    munros = munros,
                    fix = fix,
                    hasPermission = hasPermission,
                    bagged = bagged,
                    onToggleBagged = { id -> bagged = bagStore.toggle(bagged, id) },
                    tiles = tiles,
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
                    bagged = bagged,
                    onToggleBagged = { id -> bagged = bagStore.toggle(bagged, id) },
                )
            }
        }
    }
}

@Composable
private fun LayerChips(
    selected: MapLayer,
    stateFor: (MapLayer) -> PackState,
    onSelect: (MapLayer) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MapLayer.entries.forEach { l ->
            val downloaded = stateFor(l) is PackState.Ready
            FilterChip(
                selected = l == selected,
                onClick = { onSelect(l) },
                label = {
                    Text(
                        if (downloaded) l.label else "${l.label} \u2193",
                        fontSize = 13.sp,
                    )
                },
            )
        }
    }
}

@Composable
private fun PackBanner(
    layer: MapLayer,
    state: PackState,
    onDownload: () -> Unit,
) {
    // A downloaded, working layer needs no banner at all.
    if (state is PackState.Ready) return

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            when (state) {
                is PackState.Missing -> {
                    Text(
                        "${layer.label} map not downloaded",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${layer.blurb}. About ${layer.approxMb} MB \u2014 use wifi. " +
                            "Once downloaded it works with no signal.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = onDownload, shape = RoundedCornerShape(12.dp)) {
                        Text("Download", fontSize = 13.sp)
                    }
                }

                is PackState.Downloading -> {
                    Text(
                        "Downloading ${layer.label} \u2014 ${state.percent}%",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${state.mbDone} of ${state.mbTotal} MB. Keep the app open.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                is PackState.Failed -> {
                    Text(
                        "${layer.label} download failed",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(state.reason, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = onDownload, shape = RoundedCornerShape(12.dp)) {
                        Text("Try again", fontSize = 13.sp)
                    }
                }

                is PackState.Ready -> Unit
            }
        }
    }
}

/** Shown once a layer is in place, so a rebuilt pack can be pulled again. */
@Composable
private fun ReplaceLayerRow(layer: MapLayer, sizeMb: Long, onReplace: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${layer.label}: ${sizeMb} MB on phone",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onReplace, shape = RoundedCornerShape(12.dp)) {
            Text("Replace", fontSize = 12.sp)
        }
    }
}
