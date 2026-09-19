package uk.munromap.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed interface PackState {
    /** No pack on the phone yet. */
    data object Missing : PackState

    data class Downloading(val percent: Int, val mbDone: Long, val mbTotal: Long) : PackState

    data class Ready(val path: String) : PackState

    data class Failed(val reason: String) : PackState
}

object MapPack {

    /** The release asset built by .github/workflows/map-pack.yml */
    const val URL =
        "https://github.com/jersingluc-bot/Munro/releases/download/map-pack-z12/scotland.mbtiles"

    private const val FILE_NAME = "scotland.mbtiles"

    /** A real pack is ~335 MB. Anything much smaller is a failed download. */
    private const val MIN_PLAUSIBLE_BYTES = 50L * 1024 * 1024

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** Absolute path if a plausible pack is already downloaded, else null. */
    fun existingPath(context: Context): String? {
        val f = file(context)
        return if (f.isFile && f.length() > MIN_PLAUSIBLE_BYTES) f.absolutePath else null
    }

    fun delete(context: Context) {
        file(context).delete()
        File(file(context).absolutePath + ".part").delete()
    }

    /**
     * Downloads the pack, reporting progress as it goes.
     *
     * Writes to a .part file and renames only on success, so an interrupted
     * download can never be mistaken for a complete one.
     */
    suspend fun download(
        context: Context,
        onProgress: (PackState) -> Unit,
    ): PackState = withContext(Dispatchers.IO) {
        val target = file(context)
        val part = File(target.absolutePath + ".part")

        try {
            part.delete()

            val connection = (URL(URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
            }

            connection.connect()
            if (connection.responseCode !in 200..299) {
                return@withContext PackState.Failed("Server returned ${connection.responseCode}")
            }

            val total = connection.contentLengthLong
            var done = 0L
            var lastReported = -1

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
                            // Only report on whole-percent changes, to avoid
                            // recomposing the UI thousands of times.
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(
                                    PackState.Downloading(
                                        percent = pct,
                                        mbDone = done / (1024 * 1024),
                                        mbTotal = total / (1024 * 1024),
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
                    "Download finished but the file is only ${part.length() / 1024} KB"
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
