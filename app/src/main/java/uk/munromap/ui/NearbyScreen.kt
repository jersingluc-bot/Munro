package uk.munromap.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.munromap.data.Fix
import uk.munromap.data.Munro
import uk.munromap.data.formatDistance
import uk.munromap.data.nearestTo

@Composable
fun NearbyScreen(
    munros: List<Munro>,
    fix: Fix?,
    hasPermission: Boolean,
    bagged: Set<Int>,
    onToggleBagged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    ) {
    var remainingOnly by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {

        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                ) {
                Text(
                    "${bagged.size} of ${munros.size} climbed",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    )
                Text(
                    "${munros.size - bagged.size} to go",
                    style = MaterialTheme.typography.bodyMedium,
                    )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (munros.isEmpty()) 0f else bagged.size.toFloat() / munros.size },
                modifier = Modifier.fillMaxWidth(),
                )
            Spacer(Modifier.height(12.dp))
            Row {
                FilterChip(
                    selected = !remainingOnly,
                    onClick = { remainingOnly = false },
                    label = { Text("All", fontSize = 13.sp) },
                    )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = remainingOnly,
                    onClick = { remainingOnly = true },
                    label = { Text("Still to do", fontSize = 13.sp) },
                    )
            }
        }
        HorizontalDivider()

        if (fix == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (!hasPermission) {
                        "Location permission is off, so nothing can be sorted by distance."
                    } else {
                        "Waiting for a GPS fix. Go outside; this can take a minute or two."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                    )
            }
            return@Column
        }

        val visible = if (remainingOnly) munros.filter { it.id !in bagged } else munros
        val nearest = visible.nearestTo(fix.lat, fix.lon)

        LazyColumn(Modifier.fillMaxSize()) {
            items(nearest, key = { it.munro.id }) { entry ->
                val isBagged = entry.munro.id in bagged
                Row(
                    modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleBagged(entry.munro.id) }
                    .padding(start = 8.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    ) {
                    Checkbox(
                        checked = isBagged,
                        onCheckedChange = { onToggleBagged(entry.munro.id) },
                        )
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.munro.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            textDecoration = if (isBagged) TextDecoration.LineThrough else null,
                            )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${entry.munro.heightM.toInt()} m - ${entry.munro.region}",
                            style = MaterialTheme.typography.bodySmall,
                            )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            formatDistance(entry.distanceKm),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            )
                        Text(entry.compass, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
