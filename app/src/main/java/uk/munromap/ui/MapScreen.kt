package uk.munromap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.munromap.data.Fix
import uk.munromap.data.Munro
import uk.munromap.data.formatDistance
import uk.munromap.data.haversineKm
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

// Projection reference point, roughly the middle of the Scottish Highlands.
private const val LAT_REF = 57.3
private const val LON_REF = -4.6
private const val KM_PER_DEG_LAT = 110.57

private val kmPerDegLon = 111.32 * cos(Math.toRadians(LAT_REF))

/** Projects lat/lon onto a flat plane measured in kilometres. Good enough for Scotland. */
private fun projectX(lon: Double): Float = ((lon - LON_REF) * kmPerDegLon).toFloat()
private fun projectY(lat: Double): Float = (-(lat - LAT_REF) * KM_PER_DEG_LAT).toFloat()

private const val MIN_SCALE = 0.6f
private const val MAX_SCALE = 60f

@Composable
fun MapScreen(
    munros: List<Munro>,
    fix: Fix?,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary

    var scale by remember { mutableFloatStateOf(0f) } // 0 = not yet fitted
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var selected by remember { mutableStateOf<Munro?>(null) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    // Cache the projected positions once instead of recomputing every frame.
    val points = remember(munros) {
        munros.map { Triple(it, projectX(it.lon), projectY(it.lat)) }
    }

    fun fitAll(size: Size) {
        if (points.isEmpty() || size.width <= 0f || size.height <= 0f) return
        val minX = points.minOf { it.second }
        val maxX = points.maxOf { it.second }
        val minY = points.minOf { it.third }
        val maxY = points.maxOf { it.third }
        val pad = 48f
        val s = min(
            (size.width - pad * 2) / max(1f, maxX - minX),
            (size.height - pad * 2) / max(1f, maxY - minY),
        )
        scale = s
        panX = size.width / 2f - (minX + maxX) / 2f * s
        panY = size.height / 2f - (minY + maxY) / 2f * s
    }

    fun centreOn(lat: Double, lon: Double, targetScale: Float) {
        if (canvasSize == Size.Zero) return
        scale = targetScale.coerceIn(MIN_SCALE, MAX_SCALE)
        panX = canvasSize.width / 2f - projectX(lon) * scale
        panY = canvasSize.height / 2f - projectY(lat) * scale
    }

    LaunchedEffect(canvasSize, points) {
        if (scale == 0f) fitAll(canvasSize)
    }

    Box(modifier = modifier.fillMaxSize().background(surface)) {

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(points) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (scale <= 0f) return@detectTransformGestures
                        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        // Keep the point under the fingers fixed while zooming.
                        val factor = newScale / scale
                        panX = centroid.x - (centroid.x - panX) * factor + pan.x
                        panY = centroid.y - (centroid.y - panY) * factor + pan.y
                        scale = newScale
                    }
                }
                .pointerInput(points) {
                    detectTapGestures { tap ->
                        val hit = points.minByOrNull { (_, wx, wy) ->
                            val sx = wx * scale + panX
                            val sy = wy * scale + panY
                            (sx - tap.x) * (sx - tap.x) + (sy - tap.y) * (sy - tap.y)
                        }
                        if (hit != null) {
                            val sx = hit.second * scale + panX
                            val sy = hit.third * scale + panY
                            val dist = kotlin.math.hypot(sx - tap.x, sy - tap.y)
                            selected = if (dist < 48f) hit.first else null
                        }
                    }
                }
        ) {
            drawMunros(points, scale, panX, panY, selected, onSurface, primary)

            fix?.let { f ->
                val sx = projectX(f.lon) * scale + panX
                val sy = projectY(f.lat) * scale + panY
                drawLocationDot(Offset(sx, sy), f.accuracyM, scale, primary)
            }
        }

        // Controls
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            FilledTonalButton(
                onClick = {
                    if (!hasPermission) onRequestPermission()
                    else fix?.let { centreOn(it.lat, it.lon, 14f) }
                },
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(if (hasPermission) "Centre on me" else "Enable location", fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = { fitAll(canvasSize) },
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Show all", fontSize = 13.sp)
            }
        }

        // Detail card for the tapped summit
        selected?.let { m ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        m.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${m.heightM.toInt()} m  ·  ${m.heightFt} ft  ·  ${m.gridRef}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(m.region, style = MaterialTheme.typography.bodySmall)
                    fix?.let { f ->
                        val d = haversineKm(f.lat, f.lon, m.lat, m.lon)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${formatDistance(d)} away, straight line",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        FilledTonalButton(
                            onClick = { centreOn(m.lat, m.lon, 25f) },
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("Zoom to summit", fontSize = 13.sp) }
                        Spacer(Modifier.size(8.dp))
                        FilledTonalButton(
                            onClick = { selected = null },
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("Close", fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawMunros(
    points: List<Triple<Munro, Float, Float>>,
    scale: Float,
    panX: Float,
    panY: Float,
    selected: Munro?,
    baseColor: Color,
    accent: Color,
) {
    val showLabels = scale > 8f
    val paint = android.graphics.Paint().apply {
        color = baseColor.copy(alpha = 0.85f).toArgb()
        textSize = 30f
        isAntiAlias = true
    }

    points.forEach { (munro, wx, wy) ->
        val sx = wx * scale + panX
        val sy = wy * scale + panY
        // Skip anything well outside the viewport.
        if (sx < -80f || sy < -80f || sx > size.width + 80f || sy > size.height + 80f) return@forEach

        val isSelected = munro.id == selected?.id
        // Bigger dot for a bigger hill, within reason.
        val radius = (3.5f + ((munro.heightM - 900.0) / 120.0).toFloat()).coerceIn(3.5f, 8f)

        drawCircle(
            color = if (isSelected) accent else baseColor.copy(alpha = 0.55f),
            radius = if (isSelected) radius * 1.9f else radius,
            center = Offset(sx, sy),
        )
        if (isSelected) {
            drawCircle(
                color = accent.copy(alpha = 0.3f),
                radius = radius * 4f,
                center = Offset(sx, sy),
            )
        }

        if (showLabels || isSelected) {
            drawContext.canvas.nativeCanvas.drawText(
                munro.name,
                sx + radius + 8f,
                sy + 10f,
                paint,
            )
        }
    }
}

private fun DrawScope.drawLocationDot(
    centre: Offset,
    accuracyM: Float,
    scale: Float,
    accent: Color,
) {
    // Accuracy circle, drawn in real-world kilometres so it scales with the map.
    val accuracyRadiusPx = (accuracyM / 1000f) * scale
    if (accuracyRadiusPx > 6f) {
        drawCircle(
            color = accent.copy(alpha = 0.15f),
            radius = accuracyRadiusPx,
            center = centre,
        )
    }
    drawCircle(color = Color.White, radius = 11f, center = centre)
    drawCircle(color = accent, radius = 8f, center = centre)
}
