package uk.munromap.data

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Reads map tiles out of an MBTiles file.
 *
 * MBTiles is just a SQLite database with PNG blobs in it, which Android can
 * read natively — no mapping library needed.
 */
class TileSource(path: String) : Closeable {

    private val db: SQLiteDatabase =
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)

    /** Roughly 160 tiles of 256x256 RGBA is about 40 MB. */
    private val cache = object : LruCache<Long, Bitmap>(160) {
        override fun sizeOf(key: Long, value: Bitmap) = 1
    }

    /** Tiles we've looked for and know aren't there, so we don't keep asking. */
    private val absent = HashSet<Long>()

    val minZoom: Int
    val maxZoom: Int

    init {
        var lo = 0
        var hi = 12
        db.rawQuery("SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles", null).use { c ->
            if (c.moveToFirst()) {
                lo = c.getInt(0)
                hi = c.getInt(1)
            }
        }
        minZoom = lo
        maxZoom = hi
    }

    private fun key(z: Int, x: Int, y: Int): Long =
        (z.toLong() shl 58) or (x.toLong() shl 29) or y.toLong()

    /** Returns a tile only if it's already in memory. Safe to call while drawing. */
    fun cached(z: Int, x: Int, y: Int): Bitmap? = cache.get(key(z, x, y))

    fun isKnownAbsent(z: Int, x: Int, y: Int): Boolean = key(z, x, y) in absent

    /**
     * Loads one tile from disk. [y] is in XYZ convention (0 at the north edge);
     * MBTiles stores rows flipped, so it gets converted here.
     */
    suspend fun load(z: Int, x: Int, y: Int): Bitmap? = withContext(Dispatchers.IO) {
        val k = key(z, x, y)
        cache.get(k)?.let { return@withContext it }
        if (k in absent) return@withContext null

        val flippedY = (1 shl z) - 1 - y
        val bitmap = try {
            db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                arrayOf(z.toString(), x.toString(), flippedY.toString()),
            ).use { c ->
                if (c.moveToFirst()) {
                    val bytes = c.getBlob(0)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }

        if (bitmap != null) cache.put(k, bitmap) else absent.add(k)
        bitmap
    }

    override fun close() {
        cache.evictAll()
        db.close()
    }
}
