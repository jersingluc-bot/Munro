package uk.munromap.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val RELEASES = "https://github.com/jersingluc-bot/Munro/releases/download"

/**
 * A downloadable basemap. Each one is a separate MBTiles file on a GitHub
 * release, so you only keep the layers you actually want.
 */
enum class MapLayer(
    val label: String,
    val blurb: String,
    val fileName: String,
    val url: String,
    val approxMb: Int,
) {
    TERRAIN(
        label = "Terrain",
        blurb = "Shaded relief, 50 m contours and footpaths",
        fileName = "scotland-terrain.mbtiles",
        url = "$RELEASES/map-pack-z12/scotland.mbtiles",
        approxMb = 450,
    ),
    ROADS(
        label = "Roads & paths",
        blurb = "Flat map: roads, tracks, water and woodland",
        fileName = "scotland-osm.mbtiles",
        url = "$RELEASES/osm-pack-z12/scotland-osm.mbtiles",
        approxMb = 250,
    ),
}

sealed interface PackState {
    data object Missing : PackState
    data class Downloading(val percent: Int, val mbDone: Long, val mbTotal: Long) : PackState
    data class Ready(val path: String) : PackState
    data class Failed(val reason: String) : PackState
}

object MapPack {

    /** A real pack is hundreds of MB. Anything tiny is a failed download. */
    private const val MIN_PLAUSIBLE_BYTES = 20L * 1024 * 1024

    fun file(context: Context, layer: MapLayer): File = File(context.filesDir, layer.fileName)

    fun existingPath(context: Context, layer: MapLayer): String? {
        val f = file(context, layer)
        return if (f.isFile && f.length() > MIN_PLAUSIBLE_BYTES) f.absolutePath else null
    }

    fun sizeMb(context: Context, layer: MapLayer): Long =
        file(context, layer).let { if (it.isFile) it.length() / (1024 * 1024) else 0L }

    /** Removes a downloaded layer. Used by "Replace" and to free up space. */
    fun delete(context: Context, layer: MapLayer) {
        file(context, layer).delete()
        File(file(context, layer).absolutePath + ".part").delete()
    }

    /**
     * Downloads a layer, reporting progress as it goes.
     *
     * Always fetches, even if a copy already exists — that's what makes
     * "Replace" work when the pack on the server has been rebuilt. Writes to a
     * .part file and renames on success, so an interrupted download can never
     * be mistaken for a complete one.
     */
    suspend fun download(
        context: Context,
        layer: MapLayer,
        onProgress: (PackState) -> Unit,
    ): PackState = withContext(Dispatchers.IO) {
        val target = file(context, layer)
        val part = File(target.absolutePath + ".part")

        try {
            part.delete()

            val connection = (URL(layer.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
            }
            connection.connect()

            if (connection.responseCode !in 200..299) {
                val code = connection.responseCode
                connection.disconnect()
                return@withContext PackState.Failed(
                    if (code == 404) "That layer hasn't been built yet (404)"
                    else "Server returned $code"
                )
            }

            val total = connection.contentLengthLong
            var done = 0L
            var lastPct = -1

            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(
                                    PackState.Downloading(
                                        pct, done / (1024 * 1024), total / (1024 * 1024)
                                    )
                                )
                            }
                        }
                    }
                }
            }
            connection.disconnect()

            if (part.length() < MIN_PLAUSIBLE_BYTES) {
                part.delete()
                return@withContext PackState.Failed(
                    "Finished but the file is only ${part.length() / 1024} KB"
                )
            }

            target.delete()
            if (!part.renameTo(target)) {
                return@withContext PackState.Failed("Could not save the downloaded file")
            }
            PackState.Ready(target.absolutePath)
        } catch (e: Exception) {
            part.delete()
            PackState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
