package uk.munromap.data

import android.content.Context

/**
* Remembers which Munros you've climbed.
*
* Stored in SharedPreferences, in the app's private data directory. It survives
* app restarts and updates, but is wiped if you uninstall the app.
*/
class BagStore(context: Context) {

  private val prefs = context.getSharedPreferences("bagged", Context.MODE_PRIVATE)

  fun load(): Set<Int> =
  prefs.getStringSet(KEY, emptySet())
  ?.mapNotNull { it.toIntOrNull() }
  ?.toSet()
  ?: emptySet()

  fun save(ids: Set<Int>) {
    prefs.edit().putStringSet(KEY, ids.map { it.toString() }.toSet()).apply()
  }

  /** Returns the new set so the caller can hold it as state. */
  fun toggle(current: Set<Int>, id: Int): Set<Int> {
    val next = if (id in current) current - id else current + id
    save(next)
    return next
  }

  private companion object {
    const val KEY = "bagged_ids"
  }
}
