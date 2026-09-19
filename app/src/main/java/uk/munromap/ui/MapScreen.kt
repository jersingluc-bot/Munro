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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import uk.munromap.data.Fix
import uk.munromap.data.Mercator
import uk.munromap.data.Munro
import uk.munromap.data.TileSource
import uk.munromap.data.formatDistance
import uk.munromap.data.haversineKm
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

// "scale" throughout is screen pixels across the whole world at the current
// zoom. A tile at zoom z is therefore scale / 2^z pixels wide.
private const val TILE_PX = 256.0

// Scotland only: no point letting the user pan to Australia.
// Marker colours, chosen to hold up over pale hillshade and dark ground alike.
private val TO_CLIMB = Color(0xFFFF8A3D)   // amber - not yet climbed
private val CLIMBED = Color(0xFF6FE3A8)    // green - climbed
private val SELECTED = Color(0xFFFFD24A)   // yellow - currently tapped
private val OUTLINE = Color(0xFF12191D)    // near-black ring behind every marker

private const val MIN_SCALE = 4_000f
private const val MAX_SCALE = 8_000_000f

@Composable
fun MapScreen(
    munros: List<Munro>,
    fix: Fix?,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    bagged: Set<Int>,
    onToggleBagged: (Int) -> Unit,
    tiles: TileSource?,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface
    val primary = MaterialTheme.colorScheme.primary

    var scale by remember { mutableFloatStateOf(0f) } // 0 = not yet fitted
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var selected by remember { mutableStateOf<Munro?>(null) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var tileVersion by remember { mutableIntStateOf(0) }

    // Project every summit once; these are fixed for the life of the screen.
    val points = remember(munros) {
        munros.map { m ->
            Triple(m, Mercator.normX(m.lon).toFloat(), Mercator.normY(m.lat).toFloat())
        }
    }

    fun fitAll(size: Size) {
        if (points.isEmpty() || size.width <= 0f || size.height <= 0f) return
        val minX = points.minOf { it.second }
        val maxX = points.maxOf { it.second }
        val minY = points.minOf { it.third }
        val maxY = points.maxOf { it.third }
        val pad = 56f
        val s = min(
            (size.width - pad * 2) / max(1e-6f, maxX - minX),
            (size.height - pad * 2) / max(1e-6f, maxY - minY),
        ).coerceIn(MIN_SCALE, MAX_SCALE)
        scale = s
        panX = size.width / 2f - (minX + maxX) / 2f * s
        panY = size.height / 2f - (minY + maxY) / 2f * s
    }

    fun centreOn(lat: Double, lon: Double, targetScale: Float) {
        if (canvasSize == Size.Zero) return
        scale = targetScale.coerceIn(MIN_SCALE, MAX_SCALE)
        panX = canvasSize.width / 2f - Mercator.normX(lon).toFloat() * scale
        panY = canvasSize.height / 2f - Mercator.normY(lat).toFloat() * scale
    }

    LaunchedEffect(canvasSize, points) {
        if (scale == 0f) fitAll(canvasSize)
    }

    // Load whatever tiles the current view needs, off the main thread.
    LaunchedEffect(tiles, scale, panX, panY, canvasSize) {
        val source = tiles ?: return@LaunchedEffect
        if (scale <= 0f || canvasSize == Size.Zero) return@LaunchedEffect
        delay(90) // let a pinch or drag settle before hitting the disk
        val view = visibleTiles(scale, panX, panY, canvasSize, source.minZoom, source.maxZoom)
        var loadedAny = false
        for (t in view.tiles) {
            if (source.cached(view.z, t.first, t.second) != null) continue
            if (source.isKnownAbsent(view.z, t.first, t.second)) continue
            if (source.load(view.z, t.first, t.second) != null) {
                loadedAny = true
                // Redraw every few tiles so the map fills in progressively.
                if (loadedAny) tileVersion++
            }
        }
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
                        val factor = newScale / scale
                        panX = centroid.x - (centroid.x - panX) * factor + pan.x
                        panY = centroid.y - (centroid.y - panY) * factor + pan.y
                        scale = newScale
                    }
                }
                .pointerInput(points) {
                    detectTapGestures { tap ->
                        val hit = points.minByOrNull { (_, nx, ny) ->
                            val sx = nx * scale + panX
                            val sy = ny * scale + panY
                            (sx - tap.x) * (sx - tap.x) + (sy - tap.y) * (sy - tap.y)
                        }
                        if (hit != null) {
                            val sx = hit.second * scale + panX
                            val sy = hit.third * scale + panY
                            selected = if (hypot(sx - tap.x, sy - tap.y) < 48f) hit.first else null
                        }
                    }
                }
        ) {
            @Suppress("UNUSED_EXPRESSION")
            tileVersion // read it so the Canvas redraws when tiles arrive

            if (tiles != null && scale > 0f) {
                drawTiles(tiles, scale, panX, panY, size)
            }

            drawMunros(points, scale, panX, panY, selected, bagged)

            fix?.let { f ->
                val sx = Mercator.normX(f.lon).toFloat() * scale + panX
                val sy = Mercator.normY(f.lat).toFloat() * scale + panY
                drawLocationDot(Offset(sx, sy), primary)
            }
        }

        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
        ) {
            FilledTonalButton(
                onClick = {
                    if (!hasPermission) onRequestPermission()
                    else fix?.let { centreOn(it.lat, it.lon, 400_000f) }
                },
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(if (hasPermission) "Centre on me" else "Enable location", fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = { fitAll(canvasSize) },
                shape = RoundedCornerShape(12.dp),
            ) { Text("Show all", fontSize = 13.sp) }
        }

        selected?.let { m ->
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                shape = RoundedCornerShape(16.dp),
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
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${formatDistance(haversineKm(f.lat, f.lon, m.lat, m.lon))} away, straight line",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        FilledTonalButton(
                            onClick = { onToggleBagged(m.id) },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                if (m.id in bagged) "Climbed \u2713" else "Mark climbed",
                                fontSize = 13.sp,
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        FilledTonalButton(
                            onClick = { centreOn(m.lat, m.lon, 1_500_000f) },
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("Zoom", fontSize = 13.sp) }
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

private class Viewport(val z: Int, val tiles: List<Pair<Int, Int>>)

/** Which tiles, at which zoom level, cover the current view. */
private fun visibleTiles(
    scale: Float,
    panX: Float,
    panY: Float,
    size: Size,
    minZoom: Int,
    maxZoom: Int,
): Viewport {
    // Pick the zoom whose tiles are closest to their natural 256 px size.
    val z = floor(ln(scale / TILE_PX) / ln(2.0)).toInt().coerceIn(minZoom, maxZoom)
    val n = 1 shl z
    val tilePx = scale / n

    val x0 = floor(-panX / tilePx).toInt().coerceIn(0, n - 1)
    val x1 = floor((size.width - panX) / tilePx).toInt().coerceIn(0, n - 1)
    val y0 = floor(-panY / tilePx).toInt().coerceIn(0, n - 1)
    val y1 = floor((size.height - panY) / tilePx).toInt().coerceIn(0, n - 1)

    val out = ArrayList<Pair<Int, Int>>()
    for (x in x0..x1) for (y in y0..y1) out.add(x to y)
    return Viewport(z, out)
}

private fun DrawScope.drawTiles(
    source: TileSource,
    scale: Float,
    panX: Float,
    panY: Float,
    size: Size,
) {
    val view = visibleTiles(scale, panX, panY, size, source.minZoom, source.maxZoom)
    val n = 1 shl view.z
    val tilePx = scale / n

    for ((x, y) in view.tiles) {
        val bitmap = source.cached(view.z, x, y) ?: continue
        val left = x * tilePx + panX
        val top = y * tilePx + panY
        // Round the size up so neighbouring tiles don't leave hairline gaps.
        val w = ceil(tilePx).toInt()
        drawImage(
            image = bitmap.asImageBitmap(),
            dstOffset = IntOffset(floor(left).toInt(), floor(top).toInt()),
            dstSize = IntSize(w, w),
        )
    }
}

private fun DrawScope.drawMunros(
    points: List<Triple<Munro, Float, Float>>,
    scale: Float,
    panX: Float,
    panY: Float,
    selected: Munro?,
    bagged: Set<Int>,
) {
    // Labels once tiles are roughly at native size or larger.
    val showLabels = scale > 700_000f
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 30f
        isAntiAlias = true
        setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
    }

    points.forEach { (munro, nx, ny) ->
        val sx = nx * scale + panX
        val sy = ny * scale + panY
        if (sx < -80f || sy < -80f || sx > size.width + 80f || sy > size.height + 80f) return@forEach

        val isSelected = munro.id == selected?.id
        val isBagged = munro.id in bagged
        val radius = (3.5f + ((munro.heightM - 900.0) / 120.0).toFloat()).coerceIn(3.5f, 8f)
        val r = if (isSelected) radius * 1.9f else radius

        // Amber reads against both pale hillshade and dark ground; the dark
        // outline keeps it visible if it lands on something bright.
        val fill = when {
            isSelected -> SELECTED
            isBagged -> CLIMBED
            else -> TO_CLIMB
        }

        if (isBagged && !isSelected) {
            // Climbed: hollow, so progress is readable at a glance.
            drawCircle(OUTLINE, r + 1.6f, Offset(sx, sy), style = Stroke(width = 3.4f))
            drawCircle(fill, r, Offset(sx, sy), style = Stroke(width = 2.4f))
        } else {
            drawCircle(OUTLINE, r + 1.6f, Offset(sx, sy))
            drawCircle(fill, r, Offset(sx, sy))
        }
        if (isSelected) {
            drawCircle(SELECTED.copy(alpha = 0.28f), r * 2.6f, Offset(sx, sy))
        }

        if (showLabels || isSelected) {
            drawContext.canvas.nativeCanvas.drawText(munro.name, sx + r + 8f, sy + 10f, paint)
        }
    }
}

private fun DrawScope.drawLocationDot(centre: Offset, accent: Color) {
    drawCircle(Color.White, 11f, centre)
    drawCircle(accent, 8f, centre)
}
