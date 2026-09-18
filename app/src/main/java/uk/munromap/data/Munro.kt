package uk.munromap.data

import android.content.Context
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One Munro. Source: Database of British and Irish Hills (DoBIH), CC BY 3.0.
 * Grid references were converted to WGS84, so positions are good to about 100 m.
 */
data class Munro(
    val id: Int,
    val name: String,
    val heightM: Double,
    val lat: Double,
    val lon: Double,
    val gridRef: String,
    val region: String,
) {
    val heightFt: Int get() = (heightM * 3.28084).roundToInt()
}

/** A Munro paired with its distance and bearing from wherever you are. */
data class MunroWithDistance(
    val munro: Munro,
    val distanceKm: Double,
    val bearingDeg: Double,
) {
    /** Eight-point compass direction, e.g. "NE". */
    val compass: String
        get() {
            val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            val i = (((bearingDeg + 22.5) % 360.0) / 45.0).toInt()
            return points[i.coerceIn(0, 7)]
        }
}

object MunroRepository {

    @Volatile
    private var cached: List<Munro>? = null

    /** Loads and caches the bundled Munro list. Safe to call repeatedly. */
    fun load(context: Context): List<Munro> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val parsed = context.assets.open("munros.csv").bufferedReader().use { reader ->
                reader.lineSequence()
                    .drop(1) // header
                    .mapNotNull { parseLine(it) }
                    .toList()
            }
            cached = parsed
            return parsed
        }
    }

    private fun parseLine(rawLine: String): Munro? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null
        val f = splitCsv(line)
        if (f.size < 7) return null
        return try {
            Munro(
                id = f[0].toInt(),
                name = f[1],
                heightM = f[2].toDouble(),
                lat = f[3].toDouble(),
                lon = f[4].toDouble(),
                gridRef = f[5],
                region = f[6],
            )
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** Minimal CSV field splitter that understands double-quoted fields. */
    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(7)
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    out.add(sb.toString()); sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}

private const val EARTH_RADIUS_KM = 6371.0088

/** Great-circle distance in kilometres. */
fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_KM * atan2(sqrt(a), sqrt(1 - a))
}

/** Initial great-circle bearing in degrees, 0 = north. */
fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/** Munros sorted nearest-first from the given position. */
fun List<Munro>.nearestTo(lat: Double, lon: Double, limit: Int = Int.MAX_VALUE): List<MunroWithDistance> =
    map {
        MunroWithDistance(
            munro = it,
            distanceKm = haversineKm(lat, lon, it.lat, it.lon),
            bearingDeg = bearingDeg(lat, lon, it.lat, it.lon),
        )
    }
        .sortedBy { it.distanceKm }
        .take(limit)

/** Distance formatted for display: metres below 1 km, otherwise kilometres. */
fun formatDistance(km: Double): String = when {
    km < 1.0 -> "${(km * 1000).roundToInt()} m"
    km < 100.0 -> String.format("%.1f km", km)
    else -> "${km.roundToInt()} km"
}

/** Converts WGS84 back to an OS-style grid reference string for display. */
fun formatLatLon(lat: Double, lon: Double): String {
    val ns = if (lat >= 0) "N" else "S"
    val ew = if (lon >= 0) "E" else "W"
    return "${String.format("%.4f", abs(lat))}$ns  ${String.format("%.4f", abs(lon))}$ew"
}
