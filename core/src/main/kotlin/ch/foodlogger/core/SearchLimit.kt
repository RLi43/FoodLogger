package ch.foodlogger.core

/**
 * Keeps the app under Open Food Facts' search limit (10 searches a minute per user; going over it
 * can get the IP address banned), with a small margin for searches the server may count twice.
 */
object SearchLimit {
    const val MAX_SEARCHES = 8
    const val WINDOW_MS = 60_000L

    /** Milliseconds until another search may be sent, given the times of earlier searches; 0 when one may go now. */
    fun waitMillis(sentAtMillis: List<Long>, nowMillis: Long): Long {
        val recent = sentAtMillis.filter { nowMillis - it < WINDOW_MS }.sorted()
        if (recent.size < MAX_SEARCHES) return 0
        return recent[recent.size - MAX_SEARCHES] + WINDOW_MS - nowMillis
    }

    /** Adds a search sent at [nowMillis], dropping times that no longer count. */
    fun record(sentAtMillis: List<Long>, nowMillis: Long): List<Long> =
        sentAtMillis.filter { nowMillis - it < WINDOW_MS } + nowMillis
}
