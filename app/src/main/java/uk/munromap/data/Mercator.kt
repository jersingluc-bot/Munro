package uk.munromap.data

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin

/**
 * Web Mercator, normalised so the whole world is 0..1 on each axis.
 *
 * This has to match exactly how the map pack was cut (EPSG:3857, XYZ tiles),
 * or the Munro markers will sit slightly off the summits they belong to.
 */
object Mercator {

    /** Longitude to normalised X, 0 at the antimeridian, 1 at the far side. */
    fun normX(lon: Double): Double = (lon + 180.0) / 360.0

    /** Latitude to normalised Y, 0 at the north edge, 1 at the south. */
    fun normY(lat: Double): Double {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val s = sin(Math.toRadians(clamped))
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }

    /** Inverse of [normX]. */
    fun lon(normX: Double): Double = normX * 360.0 - 180.0

    /** Inverse of [normY]. */
    fun lat(normY: Double): Double {
        val n = PI * (1 - 2 * normY)
        return Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(n)))
    }
}
